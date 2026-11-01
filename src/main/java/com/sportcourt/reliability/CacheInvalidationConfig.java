package com.sportcourt.reliability;

import com.sportcourt.utils.CacheClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class CacheInvalidationConfig {
  @Bean
  public RedisMessageListenerContainer cacheInvalidations(
      RedisConnectionFactory connection, CacheClient cache) {
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connection);
    listener.addMessageListener(
        (message, pattern) -> cache.advanceLocalCacheGeneration(),
        new ChannelTopic("sportcourt.cache.invalidate"));
    return listener;
  }
}
