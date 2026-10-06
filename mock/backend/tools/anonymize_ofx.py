#!/usr/bin/env python3
"""Anonymize a T-Bank OFX statement for the mock fixture.

Keeps what the mock needs (dates, amounts, bank categories, account types, stable FITIDs) and replaces what
identifies people: account numbers, names of transfer counterparties, phone numbers, employer names.
Merchant names (Пятёрочка, Самокат, …) are kept — they are public businesses, not personal data.

    python3 anonymize_ofx.py <input.ofx> <output.ofx>
"""
import hashlib
import re
import sys

PERSON_CYR = re.compile(r"^(?:[А-ЯЁ][а-яё\-]+\s+){1,2}[А-ЯЁ]\.?$")
PERSON_LAT = re.compile(r"^(?:[A-Z][a-z\-]+\s+){1,2}[A-Z]\.?$")
PHONE = re.compile(r"\+?\d[\d\s\-()]{8,}\d")
SALARY = re.compile(r"(Пополнение\.\s*).*?(\.\s*Заработная плата)")
YOOMONEY = re.compile(r"^(Y\.M\*)(.+)$")
ACCTID = re.compile(r"<ACCTID>(.*?)</ACCTID>")
FITID = re.compile(r"<FITID>(.*?)</FITID>")
NAME = re.compile(r"<NAME>(.*?)</NAME>")

counterparties = {}


def counterparty(original: str) -> str:
    if original not in counterparties:
        counterparties[original] = f"Контрагент {len(counterparties) + 1}"
    return counterparties[original]


def anonymize_name(name: str) -> str:
    if PERSON_CYR.match(name) or PERSON_LAT.match(name):
        return counterparty(name)
    m = YOOMONEY.match(name)
    if m and PERSON_LAT.match(m.group(2).strip()):
        return m.group(1) + counterparty(m.group(2))
    if SALARY.search(name):
        return SALARY.sub(r"\1Работодатель\2", name)
    if PHONE.search(name):
        return PHONE.sub("+7 900 000-00-00", name)
    return name


def anonymize_acctid(acct: str) -> str:
    digest = hashlib.sha256(acct.encode()).hexdigest()
    if acct.startswith("OB$"):
        return f"OB$1${digest[:24]}"
    prefix = acct[:5]
    digits = "".join(str(int(c, 16) % 10) for c in digest[:15])
    return prefix + digits


def anonymize_fitid(fit: str) -> str:
    # Keep uniqueness and rough shape; FITIDs are opaque to the app.
    digest = hashlib.sha256(fit.encode()).hexdigest()
    if "-" in fit:
        return f"{digest[:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}"
    return "".join(str(int(c, 16) % 10) for c in digest[: max(12, min(len(fit), 23))])


def main(src: str, dst: str) -> None:
    text = open(src, encoding="utf-8").read()
    text = ACCTID.sub(lambda m: f"<ACCTID>{anonymize_acctid(m.group(1))}</ACCTID>", text)
    text = FITID.sub(lambda m: f"<FITID>{anonymize_fitid(m.group(1))}</FITID>", text)
    text = NAME.sub(lambda m: f"<NAME>{anonymize_name(m.group(1))}</NAME>", text)
    open(dst, "w", encoding="utf-8").write(text)
    print(f"accounts/fitids rewritten, {len(counterparties)} counterparties replaced")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
