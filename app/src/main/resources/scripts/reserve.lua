-- Atomically reserve one unit AND queue the purchase, as ONE uninterruptible step.
-- KEYS[1] = inventory key, e.g. "drop:1:remaining"
-- KEYS[2] = stream (queue) key, e.g. "purchases"
-- ARGV[1] = purchaseId, ARGV[2] = dropId, ARGV[3] = userId
-- Returns 1 if reserved and queued, 0 if sold out.

local remaining = tonumber(redis.call('GET', KEYS[1]))

if remaining == nil or remaining <= 0 then
  return 0
end

redis.call('DECR', KEYS[1])
redis.call('XADD', KEYS[2], '*',
  'purchaseId', ARGV[1],
  'dropId', ARGV[2],
  'userId', ARGV[3])
return 1
