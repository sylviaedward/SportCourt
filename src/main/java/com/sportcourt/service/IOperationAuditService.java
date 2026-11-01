package com.sportcourt.service;

public interface IOperationAuditService {
  void recordAudit(Long userId, String method, String path, Integer httpStatus);
}
