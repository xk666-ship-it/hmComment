local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 1. 判断库存是否充足（先处理 nil 情况）
local stock = redis.call("get", stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1  -- 库存不足
end

-- 2. 判断用户是否重复下单（修复命令拼写）
if redis.call('sismember', orderKey, userId) == 1 then
    return 2  -- 重复下单
end

-- 3. 扣减库存
redis.call('incrby', stockKey, -1)

-- 4. 记录用户下单
redis.call('sadd', orderKey, userId)

-- 5. 发送消息到 Stream
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0  -- 成功