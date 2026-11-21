#!/usr/bin/env python3
"""지연 조건에서 측정 전에 apiServer → dataServer 왕복 시간이 기대값과 맞는지 본다. 표준 라이브러리만 쓴다.

사용: python3 rtt_check.py <기대 지연 ms> <ping 출력 파일> <TCP 연결 시간 파일> <결과 JSON>
- ping 출력: apiServer 컨테이너에서 `ping -c N dataServer`의 출력 그대로
- TCP 연결 시간 파일: 한 줄에 "time_namelookup time_connect"(curl -w, 초)
기대: dataServer에서 나가는 쪽에만 X ms를 걸었으므로 왕복 = 바탕 왕복(브리지, 0.1ms 안팎) + X.
판정(ping): 최솟값 ≥ X − 0.05 (netem은 X보다 짧게 보내지 않는다 — 더 짧으면 지연이 안 걸린 것)
            중앙값 ≤ X + 허용(1.0 + 0.1·X ms — 머신이 바쁠 때의 흔들림)
판정(TCP 연결 = SYN → SYN-ACK 한 번의 왕복): 최솟값 ≥ X − 0.05 (TCP 패킷에도 걸렸는지)
맞으면 종료 코드 0, 아니면 1.
"""
import json
import re
import statistics
import sys


def main():
    expected = float(sys.argv[1])
    ping_text = open(sys.argv[2]).read()
    tcp_lines = [x.split() for x in open(sys.argv[3]).read().splitlines() if x.strip()]
    out = sys.argv[4]

    ping = [float(m) for m in re.findall(r"time=([0-9.]+) ms", ping_text)]
    tcp = [(float(c) - float(n)) * 1000 for n, c in tcp_lines]
    tol = 1.0 + 0.1 * expected
    reasons = []
    if not ping:
        reasons.append("ping 응답이 없다")
    if not tcp:
        reasons.append("TCP 연결 시간이 없다")
    doc = {
        "expectedRttAddedMs": expected,
        "toleranceMs": round(tol, 3),
        "rule": "ping 최솟값 ≥ 기대−0.05 · ping 중앙값 ≤ 기대+허용(1.0+0.1·기대) · TCP 연결 최솟값 ≥ 기대−0.05",
        "from": "apiServer 컨테이너", "to": "dataServer (브리지 네트워크)",
    }
    if ping:
        doc["ping"] = {"samples": len(ping), "minMs": min(ping), "medianMs": statistics.median(ping),
                       "maxMs": max(ping), "valuesMs": ping}
        if min(ping) < expected - 0.05:
            reasons.append(f"ping 최솟값 {min(ping)}ms < 기대 {expected}ms — 지연이 걸리지 않았다")
        if statistics.median(ping) > expected + tol:
            reasons.append(f"ping 중앙값 {statistics.median(ping)}ms > 기대 {expected}+{tol:.2f}ms")
    if tcp:
        tcp = [round(x, 3) for x in tcp]
        doc["tcpConnect"] = {"what": "curl time_connect − time_namelookup (dataServer:8081, SYN → SYN-ACK)",
                             "samples": len(tcp), "minMs": min(tcp), "medianMs": statistics.median(tcp),
                             "maxMs": max(tcp), "valuesMs": tcp}
        if min(tcp) < expected - 0.05:
            reasons.append(f"TCP 연결 최솟값 {min(tcp)}ms < 기대 {expected}ms")
    doc["passed"] = not reasons
    doc["failReasons"] = reasons
    with open(out, "w") as f:
        f.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    print(f"  왕복 확인: ping 중앙값 {doc.get('ping', {}).get('medianMs')}ms · "
          f"TCP 연결 중앙값 {doc.get('tcpConnect', {}).get('medianMs')}ms (기대 {expected}ms 더함) — "
          + ("맞음" if not reasons else "맞지 않음: " + "; ".join(reasons)))
    sys.exit(0 if not reasons else 1)


if __name__ == "__main__":
    main()
