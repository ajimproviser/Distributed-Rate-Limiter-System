--[[
  Sliding Window Log rate limiter - single atomic round trip.

  Every admitted request is one member of a sorted set scored by server time.
  Counting a rolling window is then "drop everything older than now - window,
  count what's left", which gives exact enforcement with no fixed-window edge
  burst (the flaw that lets a fixed counter admit 2x limit across a boundary).

  Cost: O(log N) per admit plus O(M) for the M entries aged out. Memory is one
  small member per in-window request, so this is the right choice for low limits
  where exactness matters (login attempts, OTP sends) rather than for 100K rps
  quotas - use the token bucket there.

  KEYS[1] sorted set of admitted request timestamps
  KEYS[2] idempotency marker -> replay cache for a client request id

  ARGV[1] limit                (max requests per window)
  ARGV[2] window_ms
  ARGV[3] cost                 (entries to record; <= 0 means probe without recording)
  ARGV[4] member               (unique request id, keeps entries distinct)
  ARGV[5] idempotency_ttl_ms
  ARGV[6] use_idempotency      (1 | 0)

  RETURN { allowed, remaining, retry_after_ms, reset_after_ms, replayed }
]]

local limit          = tonumber(ARGV[1])
local window_ms      = tonumber(ARGV[2])
local cost           = tonumber(ARGV[3])
local member         = ARGV[4]
local idem_ttl_ms    = tonumber(ARGV[5])
local use_idem       = tonumber(ARGV[6]) == 1

local clock        = redis.call('TIME')
local now_ms       = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)
local window_start = now_ms - window_ms

if use_idem then
  local cached = redis.call('GET', KEYS[2])
  if cached then
    local allowed, remaining, retry_after = string.match(cached, '^(%d+)|(%d+)|(%d+)$')
    if allowed then
      return { tonumber(allowed), tonumber(remaining), tonumber(retry_after), window_ms, 1 }
    end
  end
end

-- Age out everything that has fallen behind the trailing edge of the window.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. window_start)

local used = redis.call('ZCARD', KEYS[1])
if cost < 0 then cost = 0 end

local allowed        = 0
local retry_after_ms = 0

if cost == 0 then
  if used < limit then allowed = 1 end
elseif used + cost <= limit then
  allowed = 1
  for i = 1, cost do
    redis.call('ZADD', KEYS[1], now_ms, member .. ':' .. i)
  end
  used = used + cost
end

if allowed == 0 then
  -- Wait until enough of the oldest entries have aged out to fit this request.
  local needed = used + math.max(cost, 1) - limit
  local oldest = redis.call('ZRANGE', KEYS[1], needed - 1, needed - 1, 'WITHSCORES')
  if oldest[2] then
    retry_after_ms = math.max(1, (tonumber(oldest[2]) + window_ms) - now_ms)
  else
    retry_after_ms = window_ms
  end
end

redis.call('PEXPIRE', KEYS[1], window_ms + 1000)

local reset_after_ms = window_ms
local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
if first[2] then
  reset_after_ms = math.max(0, (tonumber(first[2]) + window_ms) - now_ms)
end

local remaining = math.max(0, limit - used)

if use_idem and cost > 0 then
  redis.call('SET', KEYS[2], allowed .. '|' .. remaining .. '|' .. retry_after_ms, 'PX', idem_ttl_ms)
end

return { allowed, remaining, retry_after_ms, reset_after_ms, 0 }
