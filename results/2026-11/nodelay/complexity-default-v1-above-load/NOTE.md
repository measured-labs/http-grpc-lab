# 이 폴더의 측정 (복잡도 축, 첫 측정)

이 원자료(복잡도 축, 설정 `default`, 5회, 2026-11-21 15:58~17:04)는 결과로 쓰지 않고 기록으로만 남긴다. 다섯 회차 가운데 두 번이 부하 기준을 넘은 채 시작했기 때문이다.

- 회차 1: 10분을 기다려도 1분 부하가 내려가지 않아 14.58에서 시작했다(기준 4.0).
- 회차 3: 같은 이유로 11.09에서 시작했다.

두 회차의 `conditions.json`에 `loadWait.startedAboveMax: true`로 적혀 있다. 그때 같은 머신에서 다른 프로젝트의 작업이 돌고 있었다.

부하 기준 아래에서 다시 잰 원자료는 `../complexity-default/`에 있다(기다리는 상한을 60분으로 늘려 쟀다). 서버 코드는 두 측정에서 같다.

표는 `python3 scripts/remeasure/summarize.py results/2026-11/nodelay/complexity-default-v1-above-load`로 다시 뽑을 수 있다.
