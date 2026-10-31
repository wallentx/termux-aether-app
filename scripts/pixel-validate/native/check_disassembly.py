"""Confirm the shipped Android binary contains the intended instruction paths."""
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text()
functions = dict(re.findall(r"^[0-9a-f]+ <([^>]+)>:\n(.*?)(?=^[0-9a-f]+ <|\Z)", text, re.M | re.S))
scalar = functions["scalar_sum"]
neon = functions["neon_sum"]
sve = functions["sve2_sum"]
assert not re.search(r"\b(?:v|z)[0-9]+(?:\.|\b)", scalar), "Scalar kernel contains vector registers"
assert re.search(r"\buabd\b", neon) and re.search(r"\buaddlv\b", neon), "NEON absdiff/reduction missing"
assert re.search(r"\buab(?:al|dl)b\b", sve) and re.search(r"\buab(?:al|dl)t\b", sve), "SVE2 widening differences missing"
assert not re.search(r"\bz[0-9]+\b|\bp[0-9]+\.[bhsd]\b", functions["main"]), "Dispatcher uses optional SVE registers"
print("Android scalar, NEON and SVE2 instruction paths verified")
