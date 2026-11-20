# 다시 재기(2026-11) 실행 스크립트

경로 이름과 설정(`default`, `matched`)은 `docs/remeasure/PATHS.md`에 있다.

## 준비

```bash
(cd dataServer && ./gradlew test bootJar) && (cd apiServer && ./gradlew test bootJar)
```

서버는 스크립트가 띄운다. 8080·8081·9091이 비어 있어야 하고, 1분 부하 평균이 기준(기본 4.0)보다 낮아야 시작한다.
측정 직후에는 부하 평균이 높게 남으니, 축을 이어 돌릴 때는 1~2분 쉬었다 돌린다.

## 한 축 돌리기

```bash
scripts/remeasure/run.sh --axis size --config default          # 5회, 30초, 이름표 nodelay
scripts/remeasure/run.sh --axis concurrency --config matched --rounds 5 --duration 30s
scripts/remeasure/run.sh --axis complexity --config default --label nodelay --max-load 4.0
```

| 인자 | 기본 | 뜻 |
|------|------|----|
| `--axis` | (필수) | `size` 7개 크기(동시 10) · `concurrency` 동시 10/50/100/200/500(10kb) · `complexity` 5단계(동시 10) |
| `--config` | (필수) | `default` 또는 `matched` |
| `--rounds` | 5 | 반복 횟수 |
| `--duration` | 30s | 측정 한 번의 길이 |
| `--label` | nodelay | 결과 이름표 |
| `--max-load` | 4.0 | 1분 부하 평균이 이보다 높으면 시작하지 않는다 |
| `--warmup` / `--warmup-vus` | 200 / 10 | 회차마다 조건 × 경로 하나를 몇 번, 동시 몇으로 부를지 |
| `--pause` | 3 | 측정 사이에 쉬는 초 |

회차마다: 두 서버를 새로 띄움 → 워밍업 → 조건 순서대로, 경로는 회차 r에서 r−1만큼 돌린 순서로 k6 실행 → 서버를 끔.
Ctrl-C나 오류로 멈춰도 스크립트가 띄운 서버만 끄고, 그 회차를 `status: interrupted`로 적는다.

## 결과

`results/2026-11/<이름표>/<축>-<설정>/round<r>/`

- `<조건>__<경로>.json` — k6 `--summary-export`. `<조건>__<경로>.k6.txt` — k6 출력
- `conditions.json` — 커밋, 작업 트리, JDK, k6, 코어 수, `BENCH_CONFIG` 두 줄, 실행 순서, 시각, 전후 부하 평균, 워밍업, 측정마다 실패 판정(`failed`, `failReasons`)
- `_warmup.json` — 워밍업의 k6 요약
- `dataServer.log`, `apiServer.log` — 기동 로그(`.gitignore`의 `*.log`라 커밋되지 않는다)

이미 있는 회차 폴더는 덮어쓰지 않고 멈춘다. 실패한 측정도 지우지 않는다.

## 표 뽑기

```bash
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default            # 표준 출력
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default -o table.md
```

조건마다 경로별 회차 처리량과 중앙값·최소·최대, k6 지연(중앙값, p95), 서버가 잰 하위 호출 지연, 요청당 받은 바이트,
회차별 1위와 "모든 회차에서 같음/회차마다 갈림", 1위와 처리량 구간이 겹치는지를 적는다.
