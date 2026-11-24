#!/usr/bin/env python3
"""Отметка времени приёма на каждую строку потока и запись в суточный файл <DIR>/book-YYYY-MM-DD.jsonl.

Зачем (24.11.2026). Прежде цикл `while read; do date …; done` в bnb-collect.sh запускал процесс `date` на КАЖДОЕ
сообщение (~1–3 мс на micro). В обычном потоке (70–80 сообщений/с) это успевало, а в бурю Бинанс шлёт сотни–тысячи
обновлений bookTicker в секунду: цикл отставал, сообщения копились в канале и получали время, когда до них дошла
очередь. 20.11 01:07 рывок BTC +170 б.п. записан с опозданием 83 с, в бурные часы медиана отставания 20–50 с — время
записи переставало быть временем события. Здесь отметка без порождения процессов: десятки тысяч строк в секунду.

Формат строки прежний: `<мс UTC> <сообщение>\\n`. Файл выбирается по каждой строке (дата из той же отметки) —
ночная чистка (bnb-prune.sh) жмёт вчерашний файл, писать в него после полуночи нельзя. Запись без буфера
(каждая строка сразу в файл): при перезапуске службы ничего не теряется в памяти.
"""
import os
import sys
import time

out = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/binance-book")
prefix = sys.argv[2] if len(sys.argv) > 2 else "book-"
os.makedirs(out, exist_ok=True)
day = None
f = None
inp = sys.stdin.buffer
while True:
    line = inp.readline()
    if not line:
        break
    ms = time.time_ns() // 1_000_000
    d = time.strftime("%Y-%m-%d", time.gmtime(ms // 1000))
    if d != day:
        if f is not None:
            f.close()
        f = open(os.path.join(out, prefix + d + ".jsonl"), "ab", buffering=0)
        day = d
    if not line.endswith(b"\n"):
        line += b"\n"
    f.write(b"%d " % ms + line)
if f is not None:
    f.close()
