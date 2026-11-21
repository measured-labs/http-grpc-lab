# 이 폴더의 측정 구성 (matched v1)

이 원자료(동시 사용자 축, 설정 `matched`, 5회)는 커밋 `91275bb`의 구성에서 쟀다. 그 구성에서 dataServer는 gRPC **서버 실행기**와 **서비스 코루틴의 디스패처**를 같은 512 고정 스레드 풀(`ThreadPoolExecutor(512, 512, LinkedBlockingQueue)`)로 썼다. 기동 로그에는 이렇게 찍혀 있다.

```
BENCH_CONFIG server=dataServer profile=matched read: tomcatMaxThreads=512 tomcatMaxKeepAliveRequests=-1 tomcatKeepAliveTimeoutMs=-1 | set: grpcServerExecutor=fixed(512) grpcHandlerDispatcher=grpcServerExecutor
```

이 구성 때문에 `grpc-unary`만 느려졌다. 쉬는 스레드 수백 개가 큐의 락 하나를 두고 다퉜다(jstack에서 확인). 동시 50에서 `grpc-unary`의 처리량 중앙값은 3,171 req/s였다(`http-proto`는 8,611 req/s). 서버 하위 호출 평균(apiServer가 잰 dataServer 호출 구간)은 13.9ms였다. 같은 조건에서 `default` 설정의 `grpc-unary`는 8,516 req/s, 4.0ms였다(`../concurrency-default/`).

따라서 이 폴더의 `grpc-unary` 값은 matched 설정의 gRPC 수치로 쓰지 않는다. 고친 구성(서버 실행기는 grpc-java 기본값, 서비스 코루틴만 512 고정 풀)에서 잰 원자료는 `../concurrency-matched/`에 따로 둔다. HTTP 세 경로(`http-json`, `http-bytes`, `http-proto`)의 값은 이 구성과 상관없다. gRPC 서버 실행기는 HTTP 경로를 거치지 않는다.

표는 `python3 scripts/remeasure/summarize.py results/2026-11/nodelay/concurrency-matched-v1-fixed-executor`로 다시 뽑을 수 있다.
