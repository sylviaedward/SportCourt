package com.sportcourt.enums;

/** 订场券数据库类型码。 */
public enum BookingPassType {
  ORDINARY(0),
  GOLDEN_SLOT(1);

  private final int code;

  BookingPassType(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  public boolean matches(Integer value) {
    return value != null && value == code;
  }
}
