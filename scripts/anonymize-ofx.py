#!/usr/bin/env python3
"""Produce an anonymized copy of a T-Bank OFX export for the committed mock fixture.

Keeps: amounts, dates, bank categories, merchant names, account types, operation count.
Replaces: account numbers (synthetic, same type prefix), FITIDs (hashed), personal names in NAME
(«Имя О.», «Имя Отчество Фамилия», latin «Name N.»), phone numbers, card digits.
"""
import hashlib, re, sys

src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding='utf-8').read()

acct_map = {}
def acct(m):
    a = m.group(1)
    if a not in acct_map:
        n = len(acct_map) + 1
        if a.startswith('OB$'):
            acct_map[a] = f'OB$1000{n}$' + hashlib.sha1(a.encode()).hexdigest()[:16]
        else:
            acct_map[a] = a[:5] + '810' + str(n).rjust(12, '0')
    return f'<ACCTID>{acct_map[a]}</ACCTID>'
text = re.sub(r'<ACCTID>([^<]+)</ACCTID>', acct, text)
text = re.sub(r'<FITID>([^<]+)</FITID>', lambda m: '<FITID>' + hashlib.sha1(m.group(1).encode()).hexdigest()[:18] + '</FITID>', text)

CYR_NAME = re.compile(r'^[А-ЯЁ][а-яё]+(?: [А-ЯЁ][а-яё]+)*(?: [А-ЯЁ]\.?)?$')
LAT_NAME = re.compile(r'^[A-Z][a-z]+ [A-Z]\.$')
def name(m):
    n = m.group(1)
    words = n.split()
    # A person: «Имя О.» / «Имя О» / «Имя Отчество Фамилия» (2–3 capitalized Cyrillic words, last one an initial or a full three-word name).
    is_person = CYR_NAME.match(n) and (
        (len(words) == 2 and len(words[1].rstrip('.')) == 1) or
        (len(words) == 3 and (len(words[2].rstrip('.')) == 1 or all(len(w) > 1 for w in words)))
    )
    if is_person:
        key = hashlib.sha1(n.encode()).hexdigest()[:4].upper()
        return f'<NAME>Контрагент {key}</NAME>'
    if LAT_NAME.match(n):
        return '<NAME>Contact N.</NAME>'
    n = re.sub(r'(\+7|8)[\s(]*\d{3}[\s)-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}', '+7 900 000-00-00', n)
    n = re.sub(r'\*\d{4}', '*0000', n)
    return f'<NAME>{n}</NAME>'
text = re.sub(r'<NAME>([^<]+)</NAME>', name, text)
open(dst, 'w', encoding='utf-8').write(text)
print('accounts', acct_map)
