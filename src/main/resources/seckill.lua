local voucherId = ARGV[1]
local userId = ARGV[2]
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 判断库存是否充足
if (tonumber(redis.call("get", stockKey)) <= 0) then
    return 1
end
-- 判断用户是否重复下单
if(redis.call('sismenber', orderKey, userId)==1)then
    return 2
end
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0