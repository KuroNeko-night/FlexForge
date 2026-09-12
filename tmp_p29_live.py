# -*- coding: utf-8 -*-
"""P29 live API walkthrough: multipart ask with real csv/docx/xlsx/pdf/png,
download isolation, locale packs 1.1.4/1.0.4 import+upgrade.
Credentials are read from .env inside this script and never printed."""
import http.client
import io
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
ENV = {}
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    m = re.match(r"^([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"')

HOST, PORT = "127.0.0.1", 8088


def call(method, path, body=None, token=None):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    hdr = {"Accept": "application/json"}
    if token:
        hdr["Authorization"] = "Bearer " + token
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdr["Content-Type"] = "application/json"
    conn.request(method, path, body=payload, headers=hdr)
    resp = conn.getresponse()
    raw = resp.read()
    conn.close()
    try:
        return resp.status, json.loads(raw.decode("utf-8"))
    except Exception:
        return resp.status, raw


def login(user, pw):
    status, data = call("POST", "/api/v1/auth/login", {"username": user, "password": pw})
    assert status == 200, f"login {status}"
    return data["token"]


admin = login("admin", ENV["FLEXFORGE_BOOTSTRAP_ADMIN_PASSWORD"])
demo = login("demo", ENV["FLEXFORGE_DEMO_PASS"].split("=")[-1]
             if "=" in ENV.get("FLEXFORGE_DEMO_PASS", "") else ENV["FLEXFORGE_DEMO_PASS"])


def zip_bytes(entry, content):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(entry, content)
    return buf.getvalue()


def minimal_pdf(text):
    parts = []
    offsets = []
    header = b"%PDF-1.4\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        None,  # content stream, filled below
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    stream = f"BT /F1 12 Tf 50 700 Td ({text}) Tj ET".encode()
    objects[3] = b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"
    out = bytearray(header)
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref_pos = len(out)
    out += b"xref\n0 6\n0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += (b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
            + str(xref_pos).encode() + b"\n%%EOF\n")
    return bytes(out)


PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080200000090"
    "7753de0000000c4944415408d763f8cfc00000030101"
    "00c9fe92ef0000000049454e44ae426082"
)

FILES = [
    ("files", ("report.csv", "text/csv", "item,qty\nwidget,12\ngadget,7".encode("utf-8"))),
    ("files", ("summary.docx", "application/vnd.openxmlformats-officedocument"
               ".wordprocessingml.document",
               zip_bytes("word/document.xml",
                         "<w:document><w:body><w:p><w:t>Machine downtime report: "
                         "line A stopped for 42 minutes</w:t></w:p></w:body></w:document>"))),
    ("files", ("inventory.xlsx", "application/vnd.openxmlformats-officedocument"
               ".spreadsheetml.sheet",
               zip_bytes("xl/sharedStrings.xml",
                         "<sst><si><t>spare-part-9</t></si><si><t>qty=3</t></si></sst>"))),
]

print("== multipart ask (csv+docx+xlsx) as admin ==")
boundary = "p29walk"
body = io.BytesIO()
body.write(f"--{boundary}\r\n".encode())
body.write(b'Content-Disposition: form-data; name="question"\r\n\r\n')
body.write("请汇总附件里的要点".encode("utf-8"))
body.write("\r\n".encode())
for name, (filename, ctype, data) in FILES:
    body.write(f"--{boundary}\r\n".encode())
    body.write(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
    body.write(f"Content-Type: {ctype}\r\n\r\n".encode())
    body.write(data)
    body.write("\r\n".encode())
body.write(f"--{boundary}--\r\n".encode())
conn = http.client.HTTPConnection(HOST, PORT, timeout=60)
conn.request("POST", "/api/v1/kb/ask", body=body.getvalue(), headers={
    "Authorization": "Bearer " + admin,
    "Content-Type": f"multipart/form-data; boundary={boundary}",
})
resp = conn.getresponse()
ask = json.loads(resp.read().decode("utf-8"))
conn.close()
print("status", resp.status)
print("answer:", ask.get("answer"))
print("attachments:", [a.get("filename") for a in ask.get("attachments", [])])

print("== pdf ask (extraction via pdfbox) ==")
body = io.BytesIO()
body.write(f"--{boundary}\r\n".encode())
body.write(b'Content-Disposition: form-data; name="question"\r\n\r\n')
body.write("附件里写了什么数字".encode("utf-8"))
body.write("\r\n".encode())
pdf = minimal_pdf("Quarterly revenue is 42 million")
body.write(f"--{boundary}\r\n".encode())
body.write(b'Content-Disposition: form-data; name="files"; filename="fin.pdf"\r\n')
body.write(b"Content-Type: application/pdf\r\n\r\n")
body.write(pdf)
body.write("\r\n".encode())
body.write(f"--{boundary}--\r\n".encode())
conn = http.client.HTTPConnection(HOST, PORT, timeout=60)
conn.request("POST", "/api/v1/kb/ask", body=body.getvalue(), headers={
    "Authorization": "Bearer " + admin,
    "Content-Type": f"multipart/form-data; boundary={boundary}",
})
resp = conn.getresponse()
pdf_ask = json.loads(resp.read().decode("utf-8"))
conn.close()
print("status", resp.status)
print("answer:", pdf_ask.get("answer"))

print("== bad extension rejected ==")
body = io.BytesIO()
body.write(f"--{boundary}\r\n".encode())
body.write(b'Content-Disposition: form-data; name="question"\r\n\r\nq\r\n'.replace(b"\r\nq\r\n", b"\r\n\r\nq\r\n"))
body.write(f"--{boundary}\r\n".encode())
body.write(b'Content-Disposition: form-data; name="files"; filename="evil.bat"\r\n')
body.write(b"Content-Type: application/octet-stream\r\n\r\nxx\r\n")
body.write(f"--{boundary}--\r\n".encode())
conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
conn.request("POST", "/api/v1/kb/ask", body=body.getvalue(), headers={
    "Authorization": "Bearer " + admin,
    "Content-Type": f"multipart/form-data; boundary={boundary}",
})
resp = conn.getresponse()
resp.read()
conn.close()
print("status", resp.status, "(expect 400)")

print("== attachment download owner isolation ==")
att_id = ask["attachments"][0]["id"]
status, _ = call("GET", f"/api/v1/kb/attachments/{att_id}", token=admin)
print("owner download", status, "(expect 200)")
status, _ = call("GET", f"/api/v1/kb/attachments/{att_id}", token=demo)
print("other download", status, "(expect 404)")

print("== messages replay carries attachments ==")
status, data = call("GET", "/api/v1/kb/messages", token=admin)
print(status, [(m["role"], [a["filename"] for a in m.get("attachments", [])]) for m in data][:3])

print("== clear admin conversation ==")
status, data = call("DELETE", "/api/v1/kb/messages", token=admin)
print(status, data)

print("== locale packs 1.1.4/1.0.4 import + upgrade ==")


def zipdir(path):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(path.rglob("*")):
            if f.is_file():
                zf.write(f, f.relative_to(path).as_posix())
    return buf.getvalue()


for lang in ["en", "ja", "fr", "es"]:
    pack = zipdir(ROOT / "plugins" / ("locale-" + lang))
    b = "p29pack"
    body = io.BytesIO()
    body.write(f"--{b}\r\n".encode())
    body.write(f'Content-Disposition: form-data; name="file"; filename="locale-{lang}.zip"\r\n'.encode())
    body.write(b"Content-Type: application/zip\r\n\r\n")
    body.write(pack)
    body.write(f"\r\n--{b}--\r\n".encode())
    conn = http.client.HTTPConnection(HOST, PORT, timeout=60)
    conn.request("POST", "/api/v1/plugins/import", body=body.getvalue(), headers={
        "Authorization": "Bearer " + admin,
        "Content-Type": f"multipart/form-data; boundary={b}",
    })
    resp = conn.getresponse()
    imported = json.loads(resp.read().decode("utf-8"))
    conn.close()
    status, _ = call("POST", f"/api/v1/plugins/{imported['versionId']}/upgrade", {}, token=admin)
    print(lang, "import", resp.status, imported.get("version"), "upgrade", status)
