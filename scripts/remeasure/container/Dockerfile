# 다시 재기(2026-11) 지연 조건 — 두 서버가 같이 쓰는 이미지.
# JDK 21 런타임 + tc(iproute2) + ping. jar는 넣지 않는다 — 호스트에서 빌드한 jar를 compose가 읽기 전용으로 붙인다.
# 쓰는 법: scripts/remeasure/README.md "지연 조건"
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends iproute2 iputils-ping \
 && rm -rf /var/lib/apt/lists/*
COPY entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
