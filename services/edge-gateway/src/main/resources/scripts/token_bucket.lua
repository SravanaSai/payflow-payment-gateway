-- Atomic token-bucket rate limiter.
-- KEYS[1]   = bucket key
-- ARGV[1]   = capacity (max tokens)
-- ARGV[2]   = refill rate (tokens per second)
-- ARGV[3]   = cost per request (usually 1)
-- ARGV[4]   = TTL seconds (idle bucket expiry)
-- Returns: { allowed (0|1), remaining (floor), retryAfterMillis }

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = bucket[1]
local ts = bucket[2]

if tokens == false or ts == false or tokens == nil or ts == nil then
    tokens = capacity
    ts = now
else
    tokens = tonumber(tokens)
    ts = tonumber(ts)
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)

local allowed = 0
local retry_after_ms = 0

if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
else
    if rate > 0 then
        retry_after_ms = math.ceil((cost - tokens) / rate * 1000)
    else
        retry_after_ms = ttl * 1000
    end
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('EXPIRE', key, ttl)

local remaining = math.floor(tokens)

return { allowed, remaining, retry_after_ms }
