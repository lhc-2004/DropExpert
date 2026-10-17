-- Atomically reserve one unit of inventory.
-- Redis runs this whole script as ONE step: no other command can run in the middle.
-- KEYS[1] = the inventory key, e.g. "drop:1:remaining"
-- Returns 1 if a unit was reserved, 0 if sold out.

local remaining = tonumber(redis.call('GET', KEYS[1]))

if remaining == nil or remaining <= 0 then
  return 0
end

redis.call('DECR', KEYS[1])
return 1
