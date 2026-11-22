"""Generates test PDFs (no dependencies): a 300-page text PDF and an image-only 'scanned' PDF."""
import sys, zlib

def write_pdf(path, pages):
    """pages: list of (content_bytes, resources_dict_str, extra_objects) ; extra objects appended."""
    objs = []
    def add(b):
        objs.append(b); return len(objs)
    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    pages_id = add(b"")  # placeholder
    kids = []
    for content, res_fn in pages:
        res = res_fn(add, font)
        data = zlib.compress(content)
        cid = add(b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(data) + data + b"\nendstream")
        pid = add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Resources %s /Contents %d 0 R >>" % (pages_id, res, cid))
        kids.append(pid)
    objs[pages_id - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), len(kids))
    cat = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs):
        offs.append(len(out)); out += b"%d 0 obj\n" % (i + 1) + o + b"\nendobj\n"
    x = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for o in offs: out += b"%010d 00000 n \n" % o
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, cat, x)
    open(path, "wb").write(out)

WORDS = ("the quick brown fox jumps over a lazy dog while margin notes gather along every line of this "
         "carefully typeset test document so highlighting and handwriting can be tried").split()

def text_page(n):
    lines = [b"BT /F1 18 Tf 72 730 Td (Test document - page %d) Tj ET" % n]
    y = 690
    k = n * 7
    while y > 72:
        words = [WORDS[(k + j) % len(WORDS)] for j in range(13)]
        k += 13
        lines.append(b"BT /F1 12 Tf 72 %d Td (%s) Tj ET" % (y, " ".join(words).encode()))
        y -= 18
    return b"\n".join(lines)

def text_res(add, font):
    return b"<< /Font << /F1 %d 0 R >> >>" % font

def scan_page(n):
    return b"q 540 0 0 720 36 36 cm /Im1 Do Q"

def scan_res_factory():
    # grey 'scanned text' image: stripes that look like lines, no text layer
    w, h = 540, 720
    rows = bytearray()
    for y in range(h):
        line = (y % 24) < 9 and 60 < y < 680
        for x in range(w):
            v = 70 if (line and 40 < x < 500 and (x // 7 + y // 24) % 6 != 0) else 245
            rows.append(v)
    data = zlib.compress(bytes(rows))
    cache = {}
    def res(add, font):
        if "img" not in cache:
            cache["img"] = add(b"<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length %d >>\nstream\n" % (w, h, len(data)) + data + b"\nendstream")
        return b"<< /XObject << /Im1 %d 0 R >> >>" % cache["img"]
    return res

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    write_pdf(f"{out}/margin-text-300.pdf", [(text_page(i + 1), text_res) for i in range(300)])
    r = scan_res_factory()
    write_pdf(f"{out}/margin-scanned.pdf", [(scan_page(i + 1), r) for i in range(3)])
    print("ok")
