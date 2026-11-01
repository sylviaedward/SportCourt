redis.call('INCR',KEYS[2])
redis.call('DEL',KEYS[1])
redis.call('PUBLISH','sportcourt.cache.invalidate',KEYS[1])
return 1
