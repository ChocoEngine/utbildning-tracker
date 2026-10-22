# Synthetic v1 fixture

`v1-synthetic.studytracker-backup` was created on 2026-10-22 against the v1
contract at repository revision `2e2957ad79ca7b149899375bfc3da375fdcf5c53`.
It is a hand-specified synthetic payload, packed once with Python 3 `zipfile`
(`ZIP_DEFLATED`). The two JSON members were encoded with `json.dumps` using
`ensure_ascii=False` and `separators=(',', ':')`; `payload.bytes` and SHA-256
were calculated from the exact encoded `data.json` bytes. Its ZIP SHA-256 is
`28c338c82ca971d334ef1cebc4026e580f98b6d04095e778b869cfb0512873de`.

The test asserts all portable fields independently of the production writer.
This file was **not** exported by an older application revision, so it proves
v1 reading and current round-trip, but not cross-revision compatibility. A
genuine export from an older revision would be required for that claim. The
user confirmed that no such older backup exists, so this check is outside the
current acceptance scope.
