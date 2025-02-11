package com.sog.user.application.service.user;

import java.time.Duration;


public interface RedisService {

    void setValues(String key, String value);

    void setValues(String key, String value, Duration duration);

    String getValue(String key);

    void deleteValue(String key);

}
