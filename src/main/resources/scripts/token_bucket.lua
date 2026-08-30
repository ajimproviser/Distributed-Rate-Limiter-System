--[[
  Token Bucket rate limiter - single atomic round trip.

  Tokens accrue continuously at `refill_per_ms` up to `capacity`, so a caller may
  burst up to `capacity` and then settles to the sustained rate. State is lazily
  refilled on read: no background timers, no per-key clocks to keep in sync.

  KEYS[1] bucket hash          -> fields: tokens (float), ts (server ms)
  KEYS[2] idempotency marker   -> replay cache for a client request id

  ARGV[1] capacity             (tokens)
  ARGV[2] refill_per_ms        (tokens per millisecond, decimal)
  ARGV[3] cost                 (tokens to consume; <= 0 means probe without consuming)
  ARGV[4] idempotency_ttl_ms
  ARGV[5] use_idempotency      (1 | 0)

  RETURN { allowed, remaining, retry_after_ms, reset_after_ms, replayed }

  Both keys share a hash tag, so this is cluster-safe: they always land in the
  same slot. redis.call('TIME') is the authoritative clock for every instance,
  which is what makes enforcement consistent across a horizontally scaled fleet.
]]

local capacity       = tonumber(ARGV[1])
local refill_per_ms  = tonumber(ARGV[2])
local cost           = tonumber(ARGV[3])
local idem_ttl_ms    = tonumber(ARGV[4])
local use_idem       = tonumber(ARGV[5]) == 1

local clock  = redis.call('TIME')
local now_ms = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)

-- Retried request with a previously seen id: replay the original verdict so a
-- network retry can never consume quota twice.
if use_idem then
  local cached = redis.call('GET', KEYS[2])
  if cached then
    local allowed, remaining, retry_after = string.match(cached, '^(%d+)|(%d+)|(%d+)$')
    if allowed then
      local reset_after = math.ceil((capacity - tonumber(remaining)) / refill_per_ms)
      return { tonumber(allowed), tonumber(remaining), tonumber(retry_after), reset_after, 1 }
    end
  end
end

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil or ts == nil then
  tokens = capacity
  ts     = now_ms
end

local elapsed_ms = now_ms - ts
if elapsed_ms > 0 then
  tokens = math.min(capacity, tokens + (elapsed_ms * refill_per_ms))
end

local allowed        = 0
local retry_after_ms = 0

if cost <= 0 then
  -- Probe: report whether one token is available, consume nothing.
  if tokens >= 1 then
    allowed = 1
  else
    retry_after_ms = math.ceil((1 - tokens) / refill_per_ms)
  end
elseif tokens >= cost then
  tokens  = tokens - cost
  allowed = 1
else
  retry_after_ms = math.ceil((cost - tokens) / refill_per_ms)
end

redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', now_ms)
-- Idle buckets expire once they could only have refilled to full anyway, which
-- keeps memory proportional to *active* subjects rather than all-time subjects.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms) + 1000)

local remaining      = math.floor(tokens)
local reset_after_ms = math.ceil((capacity - tokens) / refill_per_ms)

if use_idem and cost > 0 then
  redis.call('SET', KEYS[2], allowed .. '|' .. remaining .. '|' .. retry_after_ms, 'PX', idem_ttl_ms)
end

return { allowed, remaining, retry_after_ms, reset_after_ms, 0 }
