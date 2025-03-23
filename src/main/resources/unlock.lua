local id = redis.call('GET',KEYS[1])
if(id == ARGV[1]) then
    --释放锁
    return redis.call('DEL',KEYS[1])
end
return 0;