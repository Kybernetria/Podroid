# Android VM Service

Owns future Android foreground-service lifecycle and atomic enforcement of one active VM per phone. It coordinates VM core through explicit interfaces and owns Android cleanup obligations.

It does not schedule guest workloads. Current service code remains under `app/src/main/java/com/excp/podroid/service/`.
