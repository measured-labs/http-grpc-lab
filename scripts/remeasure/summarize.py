#!/usr/bin/env python3
"""다시 재기(2026-11) — run.sh가 남긴 원자료에서 표를 뽑는다. 표준 라이브러리만 쓴다.

사용: python3 scripts/remeasure/summarize.py results/2026-11/<이름표>/<축>-<설정> [-o 파일.md]

조건마다, 경로마다: 회차별 처리량(req/s)과 그 중앙값·최솟값·최댓값, k6가 잰 지연의 중앙값과 p95
(회차들의 중앙값), 서버가 잰 하위 호출 지연의 평균(회차들의 중앙값), 요청당 받은 바이트.
조건마다: 회차별 1위 경로, 모든 회차에서 같은 경로가 1위였는지, 1위와 처리량 구간이 겹치는지.
"""
import argparse
import json
import statistics
import sys
from pathlib import Path


def fmt(x, nd=1):
    if x is None:
        return "—"
    if isinstance(x, float):
        return f"{x:,.{nd}f}"
    return f"{x:,}"


def median_of(per_round: dict, key: str):
    vals = [x[key] for x in per_round.values() if x.get(key) is not None]
    return statistics.median(vals) if vals else None


def load_rounds(axis_dir: Path):
    rounds = []
    for d in sorted(axis_dir.glob("round*"), key=lambda p: int(p.name[5:]) if p.name[5:].isdigit() else 0):
        cond_file = d / "conditions.json"
        cond = json.loads(cond_file.read_text()) if cond_file.exists() else None
        rounds.append((int(d.name[5:]), d, cond))
    return rounds


def read_measure(path: Path):
    m = json.loads(path.read_text())["metrics"]
    reqs = m["http_reqs"]["count"]
    dur = m.get("http_req_duration", {})
    srv = m.get("server_call_ms", {})
    return {
        "rps": m["http_reqs"]["rate"],
        "requests": reqs,
        "med": dur.get("med"),
        "p95": dur.get("p(95)"),
        "server_avg": srv.get("avg"),
        "bytes_per_req": m["data_received"]["count"] / reqs if reqs else None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("axis_dir")
    ap.add_argument("-o", "--output")
    args = ap.parse_args()
    axis_dir = Path(args.axis_dir)
    rounds = load_rounds(axis_dir)
    if not rounds:
        sys.exit(f"회차 폴더가 없다: {axis_dir}")

    out = []
    warn = []
    w = out.append

    conds_meta = [c for _, _, c in rounds if c]
    if len(conds_meta) < len(rounds):
        warn.append("conditions.json이 없는 회차가 있다: "
                    + ", ".join(f"round{r}" for r, _, c in rounds if not c))
    first = conds_meta[0] if conds_meta else {}

    # 조건과 경로의 순서: conditions.json의 조건 목록과 회차 1의 실행 순서
    cond_names = [c["name"] for c in first.get("conditions", [])]
    paths = list(first.get("pathOrder", []))
    for r, d, _ in rounds:
        for f in sorted(d.glob("*__*.json")):
            cn, pn = f.stem.split("__", 1)
            if cn not in cond_names:
                cond_names.append(cn)
            if pn not in paths:
                paths.append(pn)

    # 실패 표시: conditions.json의 판정
    failed = {}
    for r, d, c in rounds:
        for m in (c or {}).get("measurements", []):
            if m.get("failed"):
                failed[(r, m["condition"], m["path"])] = "; ".join(m.get("failReasons", []))
        ip = (c or {}).get("inProgress")
        if ip:
            failed[(r, ip["condition"], ip["path"])] = "회차가 이 측정 도중에 멈춤"

    # ---- 측정 조건 요약 ----
    axis = first.get("axis", axis_dir.name)
    w(f"# {axis_dir.parent.name} / {axis_dir.name}")
    w("")
    w("## 측정 조건")
    w("")
    commits = sorted({c["commit"] for c in conds_meta})
    w(f"- 커밋: {', '.join(commits)}" + (" (회차마다 다름 — 확인 필요)" if len(commits) > 1 else ""))
    dirty = [c["round"] for c in conds_meta if not c.get("trackedFilesClean", True)]
    w(f"- 작업 트리: " + ("깨끗함(추적 파일 변경 없음)" if not dirty else f"추적 파일이 바뀐 채로 돈 회차 {dirty}"))
    w(f"- 축 {axis} · 설정 {first.get('config')} · 회차 {len(rounds)} · 한 번 {first.get('duration')} · "
      f"워밍업 대상마다 {first.get('warmup', {}).get('iterationsPerTarget')}회(동시 {first.get('warmup', {}).get('vus')})")
    w(f"- {first.get('jdk')} · {first.get('k6')} · 코어 {first.get('cores')} · {first.get('machine', '')}")
    loads = [x for c in conds_meta for x in (c.get("load1Before"), c.get("load1After")) if x is not None]
    if loads:
        before = [c["load1Before"] for c in conds_meta if c.get("load1Before") is not None]
        after = [c["load1After"] for c in conds_meta if c.get("load1After") is not None]
        w(f"- 1분 부하 평균: 시작 전 {min(before):.2f}~{max(before):.2f}, 끝난 뒤 "
          + (f"{min(after):.2f}~{max(after):.2f}" if after else "—"))
    statuses = {c["round"]: c.get("status") for c in conds_meta}
    if any(s != "complete" for s in statuses.values()):
        warn.append(f"끝나지 않은 회차: {[r for r, s in statuses.items() if s != 'complete']} ({statuses})")
    bench_sets = {tuple(c.get("benchConfig", [])) for c in conds_meta}
    w("- BENCH_CONFIG" + (" (회차마다 다름 — 확인 필요)" if len(bench_sets) > 1 else "") + ":")
    w("")
    w("```")
    for bc in bench_sets:
        for line in bc:
            w(line)
    w("```")
    w("")
    w("- 실행 순서(회차별): " + " · ".join(f"r{c['round']} {' '.join(c.get('pathOrder', []))}" for c in conds_meta))
    w("- 처리량 = k6 `http_reqs.rate`. 지연 = k6 `http_req_duration`의 중앙값과 p95(회차 값들의 중앙값). "
      "서버 하위 호출 = apiServer 응답의 `latencyMs`(dataServer 호출 구간) 평균(회차 값들의 중앙값). "
      "받은 바이트 = k6 `data_received` ÷ 요청 수(apiServer → k6 응답, dataServer → apiServer 전송량이 아님).")
    w("- ⚠ 표시 = 실패로 판정된 측정(k6 종료 코드 ≠ 0, 요청 실패율 ≠ 0, check 실패). 값은 그대로 표에 넣었다.")
    w("")

    # ---- 조건별 표 ----
    w("## 결과")
    for cn in cond_names:
        data = {}  # path -> {round: measure}
        for r, d, _ in rounds:
            for pn in paths:
                f = d / f"{cn}__{pn}.json"
                if f.exists():
                    try:
                        data.setdefault(pn, {})[r] = read_measure(f)
                    except (KeyError, ValueError) as e:
                        warn.append(f"읽지 못함: {f} ({e})")
        if not data:
            continue
        counts = {pn: len(v) for pn, v in data.items()}
        if len(set(counts.values())) > 1 or set(counts) != set(paths):
            warn.append(f"{cn}: 경로마다 회차 수가 다르다 {counts}")
        round_ids = sorted({r for v in data.values() for r in v})

        stats = {}
        for pn, v in data.items():
            rps = [v[r]["rps"] for r in sorted(v)]
            stats[pn] = {
                "rps": rps,
                "med": statistics.median(rps), "min": min(rps), "max": max(rps),
                "lat_med": median_of(v, "med"),
                "lat_p95": median_of(v, "p95"),
                "srv": median_of(v, "server_avg"),
                "bpr": median_of(v, "bytes_per_req"),
            }
        top = max(stats, key=lambda p: stats[p]["med"])
        winners = {}
        for r in round_ids:
            cands = {pn: data[pn][r]["rps"] for pn in data if r in data[pn]}
            winners[r] = max(cands, key=cands.get)
        same = len(set(winners.values())) == 1 and len(winners) == len(rounds)

        w("")
        w(f"### {cn}")
        w("")
        head = ["경로"] + [f"r{r} req/s" for r in round_ids] + \
               ["중앙값", "최소", "최대", "1위와 구간", "지연 중앙값 ms", "지연 p95 ms", "서버 하위 호출 평균 ms", "받은 바이트/요청"]
        w("| " + " | ".join(head) + " |")
        w("|" + "|".join(["------"] * len(head)) + "|")
        for pn in [p for p in paths if p in stats]:
            s = stats[pn]
            cells = []
            for r in round_ids:
                if r in data[pn]:
                    mark = " ⚠" if (r, cn, pn) in failed else ""
                    cells.append(fmt(data[pn][r]["rps"]) + mark)
                else:
                    cells.append("없음")
            if pn == top:
                ov = "(1위)"
            else:
                t = stats[top]
                ov = "겹침" if s["max"] >= t["min"] and t["max"] >= s["min"] else "안 겹침"
            w("| " + " | ".join([f"`{pn}`"] + cells + [fmt(s["med"]), fmt(s["min"]), fmt(s["max"]), ov,
                                                          fmt(s["lat_med"], 3), fmt(s["lat_p95"], 3),
                                                          fmt(s["srv"], 3), fmt(s["bpr"], 1)]) + " |")
        w("")
        w("- 회차별 1위: " + ", ".join(f"r{r} `{p}`" for r, p in winners.items())
          + f" → **{'모든 회차에서 같음' if same else '회차마다 갈림'}**")
        for (r, c2, p2), why in sorted(failed.items()):
            if c2 == cn:
                w(f"- ⚠ r{r} `{p2}`: {why}")

    if warn:
        out.insert(0, "")
        for x in reversed(warn):
            out.insert(0, f"> 경고: {x}")
        for x in warn:
            print(f"경고: {x}", file=sys.stderr)

    text = "\n".join(out) + "\n"
    if args.output:
        Path(args.output).write_text(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
