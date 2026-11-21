#!/usr/bin/env python3
"""run.sh가 회차마다 부른다 — 회차의 실행 조건과 측정 목록을 conditions.json으로 적는다.

사용: ROUND_STATUS=<상태> python3 conditions.py <상태 폴더> <회차 폴더>
공통 값(커밋, JDK 등)은 run.sh가 환경 변수로 넘긴다. 표준 라이브러리만 쓴다.
"""
import json
import os
import sys
from pathlib import Path


def read(state: Path, name: str, default=None):
    p = state / name
    return p.read_text().strip() if p.exists() else default


def lines(state: Path, name: str):
    v = read(state, name)
    return [x for x in v.splitlines() if x] if v else []


def rate_value(metric):
    """k6 요약의 Rate 지표 값(비율). 형식이 달라도 읽히게 value → rate 순으로 본다."""
    if not metric:
        return None
    for k in ("value", "rate"):
        if k in metric:
            return metric[k]
    return None


def judge(round_dir: Path, m: dict):
    """측정 하나를 판정한다. 실패여도 파일은 지우지 않고 표시만 한다."""
    reasons = []
    f = round_dir / m["file"]
    if m["k6Exit"] != 0:
        reasons.append(f"k6 종료 코드 {m['k6Exit']}")
    if not f.exists():
        reasons.append("요약 JSON 없음")
    else:
        try:
            metrics = json.loads(f.read_text())["metrics"]
            failed_rate = rate_value(metrics.get("http_req_failed"))
            m["httpReqFailedRate"] = failed_rate
            m["requests"] = metrics.get("http_reqs", {}).get("count")
            checks = metrics.get("checks") or {}
            m["checksFailed"] = checks.get("fails")
            if failed_rate is None:
                reasons.append("http_req_failed 없음")
            elif failed_rate != 0:
                reasons.append(f"요청 실패율 {failed_rate}")
            if checks.get("fails"):
                reasons.append(f"check 실패 {checks['fails']}건")
            if not m["requests"]:
                reasons.append("요청 0건")
        except (ValueError, KeyError) as e:
            reasons.append(f"요약 JSON을 읽지 못함: {e}")
    m["failed"] = bool(reasons)
    m["failReasons"] = reasons
    return m


def container_info(state: Path, env):
    """컨테이너 방식(--delay)일 때의 구성과 측정 전 왕복 확인. 호스트 직접 실행이면 None."""
    if env.get("CONTAINER") != "1":
        return None
    rtt = read(state, "rtt_check.json")
    delay = int(env["DELAY"])
    return {
        "composeProject": env.get("COMPOSE_PROJECT"), "composeFile": "docker-compose.remeasure.yml",
        "delayMs": delay,
        "delayMeaning": f"dataServer 컨테이너의 eth0에서 나가는 패킷(dataServer → apiServer 방향)에만 tc netem delay {delay}ms. "
                        f"apiServer → dataServer 방향은 지연 없음. 왕복(요청 하나의 왕복, TCP 핸드셰이크 한 번)에 {delay}ms가 더해진다. "
                        "k6 → apiServer 구간에는 걸리지 않는다",
        "netemQdisc": read(state, "qdisc"),
        "rttCheck": json.loads(rtt) if rtt else None,
        "image": env.get("IMAGE_TAG"), "imageId": env.get("IMAGE_ID"), "baseImage": env.get("BASE_IMAGE"),
        "jdkInImage": env.get("CONTAINER_JDK"),
        "docker": env.get("DOCKER_VERSION"), "compose": env.get("COMPOSE_VERSION"),
        "network": "compose 기본 브리지 네트워크(rm-http-grpc_default). apiServer는 dataServer를 서비스 이름으로 부른다",
        "ports": "호스트에는 apiServer 8080만 127.0.0.1로 연다(docker 포트 전달). dataServer 포트는 열지 않는다",
        "limits": "CPU·메모리 제한 없음. dataServer는 NET_ADMIN 권한만 더함(privileged 아님)",
        "jars": "호스트에서 빌드한 jar를 읽기 전용으로 붙인다(jars 항목과 같은 파일)",
    }


def main():
    state, round_dir = Path(sys.argv[1]), Path(sys.argv[2])
    env = os.environ
    measurements = []
    for row in lines(state, "measurements.tsv"):
        seq, cond, path, param, vus, t0, t1, rc = row.split("\t")
        measurements.append(judge(round_dir, {
            "seq": int(seq), "condition": cond, "path": path, "param": param, "vus": int(vus),
            "file": f"{cond}__{path}.json", "startedAt": t0, "endedAt": t1, "k6Exit": int(rc),
        }))
    in_progress = None
    cur = read(state, "current")
    if cur:
        seq, cond, path, param, vus, t0 = cur.split("\t")
        in_progress = {"seq": int(seq), "condition": cond, "path": path, "startedAt": t0,
                       "file": f"{cond}__{path}.json",
                       "note": "회차가 이 측정 도중에 멈췄다. 파일이 있으면 끊긴 측정의 요약이다"}

    conditions = []
    for c in lines(state, "conditions"):
        name, param, vus = c.split("|")
        conditions.append({"name": name, "param": param, "vus": int(vus)})

    warmup_exit = read(state, "warmup_exit")
    tracked_dirty = [x for x in env.get("TRACKED_DIRTY", "").splitlines() if x]
    loadavg_before = read(state, "loadavg_before", "")
    loadavg_after = read(state, "loadavg_after", "")
    doc = {
        "status": env.get("ROUND_STATUS", "unknown"),
        "axis": env["AXIS"], "config": env["CONFIG"], "label": env["LABEL"],
        "round": int(round_dir.name.removeprefix("round")), "roundsPlanned": int(env["ROUNDS"]),
        "commit": env["COMMIT"],
        "trackedFilesClean": not tracked_dirty, "trackedFilesDirty": tracked_dirty,
        "jars": [x for x in env.get("JARS", "").splitlines() if x],
        # true = 시작할 때 이 커밋에서 ./gradlew bootJar로 빌드한 jar로 쟀다
        "jarsBuiltAtStart": env.get("BUILD") == "1",
        # true = 추적 파일에 커밋하지 않은 변경(docs/plans/ 제외)이 있어도 시작하게 했다(--allow-dirty)
        "allowDirty": env.get("ALLOW_DIRTY") == "1",
        "onlyConditions": [x for x in env.get("ONLY_CONDITIONS", "").split(",") if x] or None,
        "jdk": env["JDK"], "k6": env["K6V"], "cores": int(env["CORES"]), "cpuModel": env["CPU_MODEL"],
        "machine": "k6와 두 서버(apiServer, dataServer)가 같은 머신에서 돈다",
        # host-jar = 호스트에서 java -jar로 직접 · docker-compose = 두 서버를 컨테이너로(--delay)
        "execution": "docker-compose" if env.get("CONTAINER") == "1" else "host-jar",
        "container": container_info(state, env),
        "benchConfig": lines(state, "bench_config"),
        "conditions": conditions,
        "pathOrder": lines(state, "order"),
        "startedAt": read(state, "started_at"), "endedAt": read(state, "ended_at"),
        "load1Before": float(read(state, "load_before")) if read(state, "load_before") else None,
        "load1After": float(read(state, "load_after")) if read(state, "load_after") else None,
        "loadavgBefore": " ".join(loadavg_before.split()[:3]),
        "loadavgAfter": " ".join(loadavg_after.split()[:3]),
        "maxLoad1": float(env["MAX_LOAD"]),
        "loadWait": {
            # 회차를 시작하기 전에 1분 부하 평균이 maxLoad1 아래로 내려가길 기다린 시간
            "waitedSeconds": int(read(state, "load_wait_seconds")) if read(state, "load_wait_seconds") else None,
            # true = 최대 대기 시간을 넘어 기준을 넘은 채 시작했다(그때의 값 = load1Before)
            "startedAboveMax": read(state, "load_wait_exceeded") == "1" if read(state, "load_wait_exceeded") else None,
            "maxWaitSeconds": int(env["MAX_WAIT"]),
            "checkIntervalSeconds": int(env["LOAD_CHECK_INTERVAL"]),
        },
        "topProcessesBefore": lines(state, "top_before"),
        "warmup": {
            "iterationsPerTarget": int(env["WARMUP"]), "vus": int(env["WARMUP_VUS"]),
            "targets": (read(state, "warmup_targets") or "").split(",") if read(state, "warmup_targets") else [],
            "k6Exit": int(warmup_exit) if warmup_exit is not None else None,
            "file": "_warmup.json",
        },
        "duration": env["DURATION"], "pauseSeconds": float(env["PAUSE"]),
        "measurements": measurements,
        "failedCount": sum(1 for m in measurements if m["failed"]),
        "inProgress": in_progress,
    }
    tmp = round_dir / "conditions.json.tmp"
    tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    tmp.replace(round_dir / "conditions.json")


if __name__ == "__main__":
    main()
