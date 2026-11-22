#!/usr/bin/env python3
"""지연 조건에서 측정 전에 qdisc와 apiServer → dataServer 왕복 시간이 기대와 맞는지 본다. 표준 라이브러리만 쓴다.

사용: python3 rtt_check.py <기대 지연 ms> <ping 출력 파일> <TCP 연결 시간 파일> <결과 JSON> \
          <qdisc 파일> <qdisc 명령 종료 코드> <ping 명령 종료 코드> <TCP 명령 종료 코드>
- ping 출력: apiServer 컨테이너에서 `ping -c 20 dataServer`의 표준 출력 그대로
- TCP 연결 시간 파일: 한 줄에 "time_namelookup time_connect"(curl -w, 초) — 10줄
- qdisc 파일: dataServer 컨테이너에서 `tc qdisc show dev eth0`의 표준 출력
기대: dataServer에서 나가는 쪽에만 X ms를 걸었으므로 왕복 = 바탕 왕복(브리지, 0.1ms 안팎) + X.
판정(명령): 세 확인 명령의 종료 코드가 모두 0
판정(qdisc): root netem, delay X ms(tc는 지연 0이면 delay를 출력하지 않는다 — 없으면 0으로 읽는다), limit 100000
판정(ping): 응답 20개, 손실 0%, 최솟값 ≥ X − 0.05 (netem은 X보다 짧게 보내지 않는다 — 더 짧으면 지연이 안 걸린 것)
            중앙값 ≤ X + 허용(1.0 + 0.1·X ms — 머신이 바쁠 때의 흔들림)
판정(TCP 연결 = SYN → SYN-ACK 한 번의 왕복): 형식에 맞는 줄 10개, 형식 밖의 줄 없음, 최솟값 ≥ X − 0.05
어떤 경우든 결과 JSON에 passed와 failReasons를 남긴다. 맞으면 종료 코드 0, 아니면 1.
"""
import json
import re
import statistics
import sys

PING_N = 20  # run.sh의 ping -c 와 같게
TCP_N = 10   # run.sh의 curl 반복 횟수와 같게
QDISC_LIMIT = "100000"  # entrypoint.sh의 limit 와 같게


def read_text(path, reasons, what):
    try:
        with open(path) as f:
            return f.read()
    except OSError as e:
        reasons.append(f"{what} 파일을 읽지 못함: {e}")
        return ""


def check_qdisc(text, expected, reasons):
    roots = [x.strip() for x in text.splitlines() if " root " in f" {x} "]
    doc = {"raw": text.strip(), "rootLine": roots[0] if roots else None}
    if len(roots) != 1:
        reasons.append(f"qdisc의 root 줄이 하나가 아니다({len(roots)}개)")
        return doc
    line = roots[0]
    if not re.match(r"qdisc netem \S+ root\b", line):
        reasons.append(f"root qdisc가 netem이 아니다: {line}")
    if not re.search(rf"\blimit {QDISC_LIMIT}\b", line):
        reasons.append(f"qdisc에 limit {QDISC_LIMIT}이 없다: {line}")
    m = re.search(r"\bdelay ([0-9.]+)(us|ms|s)\b", line)
    if m:
        delay_ms = float(m.group(1)) * {"us": 0.001, "ms": 1.0, "s": 1000.0}[m.group(2)]
    elif re.search(r"\bdelay\b", line):
        delay_ms = None
        reasons.append(f"qdisc의 delay를 읽지 못함: {line}")
    else:
        delay_ms = 0.0  # tc는 지연 0을 출력하지 않는다
    doc["delayMs"] = delay_ms
    if delay_ms is not None and abs(delay_ms - expected) > 1e-6:
        reasons.append(f"qdisc의 지연 {delay_ms}ms ≠ 기대 {expected}ms")
    return doc


def check_ping(text, expected, tol, reasons):
    ping = []
    for m in re.finditer(r"time=(\S+) ms", text):
        try:
            ping.append(float(m.group(1)))
        except ValueError:
            reasons.append(f"ping의 time 값을 읽지 못함: {m.group(1)}")
    doc = {"samples": len(ping), "expectedSamples": PING_N}
    s = re.search(r"(\d+) packets transmitted, (\d+) (?:packets )?received.*?([0-9.]+)% packet loss", text)
    if s:
        doc.update(transmitted=int(s.group(1)), received=int(s.group(2)), lossPercent=float(s.group(3)))
        if float(s.group(3)) != 0:
            reasons.append(f"ping 손실 {s.group(3)}%")
    else:
        reasons.append("ping 요약 줄(packets transmitted … packet loss)이 없다")
    if len(ping) != PING_N:
        reasons.append(f"ping 응답 {len(ping)}개 ≠ 기대 {PING_N}개")
    if ping:
        med = statistics.median(ping)
        doc.update(minMs=min(ping), medianMs=med, maxMs=max(ping), valuesMs=ping)
        if min(ping) < expected - 0.05:
            reasons.append(f"ping 최솟값 {min(ping)}ms < 기대 {expected}ms — 지연이 걸리지 않았다")
        if med > expected + tol:
            reasons.append(f"ping 중앙값 {med}ms > 기대 {expected}+{tol:.2f}ms")
    return doc


def check_tcp(text, expected, reasons):
    tcp, bad = [], []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split()
        try:
            if len(parts) != 2:
                raise ValueError
            n, c = float(parts[0]), float(parts[1])
            tcp.append(round((c - n) * 1000, 3))
        except ValueError:
            bad.append(line[:200])
    doc = {"what": "curl time_connect − time_namelookup (dataServer:8081, SYN → SYN-ACK)",
           "samples": len(tcp), "expectedSamples": TCP_N}
    if bad:
        doc["badLines"] = bad
        reasons.append(f"TCP 연결 시간 파일에 형식 밖의 줄 {len(bad)}개")
    if len(tcp) != TCP_N:
        reasons.append(f"TCP 연결 시간 {len(tcp)}개 ≠ 기대 {TCP_N}개")
    if tcp:
        doc.update(minMs=min(tcp), medianMs=round(statistics.median(tcp), 3), maxMs=max(tcp), valuesMs=tcp)
        if min(tcp) < expected - 0.05:
            reasons.append(f"TCP 연결 최솟값 {min(tcp)}ms < 기대 {expected}ms")
    return doc


def main():
    reasons = []
    doc = {}
    out = sys.argv[4] if len(sys.argv) > 4 else None
    try:
        if len(sys.argv) != 9:
            raise ValueError(f"인자 수가 맞지 않다({len(sys.argv) - 1}개 — 8개)")
        expected = float(sys.argv[1])
        tol = 1.0 + 0.1 * expected
        doc.update({
            "expectedRttAddedMs": expected,
            "toleranceMs": round(tol, 3),
            "rule": f"세 명령 종료 코드 0 · qdisc root netem, delay 기대값, limit {QDISC_LIMIT} · "
                    f"ping 응답 {PING_N}개, 손실 0 · ping 최솟값 ≥ 기대−0.05 · ping 중앙값 ≤ 기대+허용(1.0+0.1·기대) · "
                    f"TCP 연결 {TCP_N}개, 형식 밖의 줄 없음 · TCP 연결 최솟값 ≥ 기대−0.05",
            "from": "apiServer 컨테이너", "to": "dataServer (브리지 네트워크)",
        })
        codes = {"qdisc": sys.argv[6], "ping": sys.argv[7], "tcp": sys.argv[8]}
        doc["exitCodes"] = codes
        for k, v in codes.items():
            if v != "0":
                reasons.append(f"{k} 확인 명령의 종료 코드 {v}")
        doc["qdisc"] = check_qdisc(read_text(sys.argv[5], reasons, "qdisc"), expected, reasons)
        doc["ping"] = check_ping(read_text(sys.argv[2], reasons, "ping"), expected, tol, reasons)
        doc["tcpConnect"] = check_tcp(read_text(sys.argv[3], reasons, "TCP"), expected, reasons)
    except Exception as e:  # 어떤 경우든 판정을 남긴다
        reasons.append(f"확인 중 예외: {type(e).__name__}: {e}")
    doc["passed"] = not reasons
    doc["failReasons"] = reasons
    if out:
        try:
            with open(out, "w") as f:
                f.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
        except OSError as e:
            print(f"  왕복 확인: 결과를 쓰지 못함 {out}: {e}", file=sys.stderr)
            sys.exit(1)
    print(f"  왕복 확인: qdisc 지연 {doc.get('qdisc', {}).get('delayMs')}ms · "
          f"ping 중앙값 {doc.get('ping', {}).get('medianMs')}ms · "
          f"TCP 연결 중앙값 {doc.get('tcpConnect', {}).get('medianMs')}ms (기대 {doc.get('expectedRttAddedMs')}ms 더함) — "
          + ("맞음" if not reasons else "맞지 않음: " + "; ".join(reasons)))
    sys.exit(0 if not reasons else 1)


if __name__ == "__main__":
    main()
