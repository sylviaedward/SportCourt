package com.sportcourt;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.sportcourt.mapper")
@SpringBootApplication
public class SportCourtApplication {

  public static void main(String[] args) {
    SpringApplication.run(SportCourtApplication.class, args);
  }
}
