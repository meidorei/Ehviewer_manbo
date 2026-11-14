"""独立验证 JM 移动端 API，不依赖 EhViewer 的网络客户端。

运行示例：
    python tools/jm_query_demo.py JM350234

依赖：
    pip install curl-cffi pycryptodome
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import time
from typing import Any
from urllib.parse import urlencode

from Crypto.Cipher import AES
from curl_cffi.requests import Session


API_DOMAINS = (
    "www.cdnhjk.net",
    "www.cdngwc.cc",
    "www.cdngwc.net",
    "www.cdngwc.club",
    "www.cdnutc.me",
)
DEFAULT_APP_VERSION = "2.0.28"
SECRET = "185Hcomic3PAPP7R"
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Version/4.0 Chrome/113.0 Mobile Safari/537.36"
)


def parse_jm_id(value: str) -> str:
    text = value.strip()
    if text.isdigit():
        return text
    match = re.fullmatch(r"jm(\d+)", text, re.IGNORECASE)
    if match:
        return match.group(1)
    for pattern in (r"(?:photos?|albums?)/(\d+)", r"[?&]id=(\d+)"):
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1)
    raise ValueError(f"无法解析 JM 号：{value!r}")


def md5_hex(value: str) -> str:
    return hashlib.md5(value.encode("utf-8")).hexdigest()


def decrypt_data(encoded: str, timestamp: str) -> dict[str, Any]:
    ciphertext = base64.b64decode(encoded, validate=True)
    key = md5_hex(timestamp + SECRET).encode("ascii")
    padded = AES.new(key, AES.MODE_ECB).decrypt(ciphertext)
    padding = padded[-1]
    if not 1 <= padding <= AES.block_size:
        raise ValueError("非法 PKCS#7 padding")
    if padded[-padding:] != bytes([padding]) * padding:
        raise ValueError("PKCS#7 padding 不一致")
    value = json.loads(padded[:-padding].decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("解密结果不是 JSON 对象")
    return value


def parse_outer_json(text: str) -> dict[str, Any]:
    begin = text.find("{")
    end = text.rfind("}")
    if begin < 0 or end < begin:
        raise ValueError("响应不是 JSON")
    value = json.loads(text[begin : end + 1])
    if not isinstance(value, dict):
        raise ValueError("外层响应不是 JSON 对象")
    return value


def request_encrypted(
    session: Session,
    domain: str,
    path: str,
    version: str,
    params: dict[str, str] | None = None,
) -> dict[str, Any]:
    timestamp = str(int(time.time()))
    query = f"?{urlencode(params)}" if params else ""
    response = session.get(
        f"https://{domain}{path}{query}",
        headers={
            "Accept-Encoding": "gzip, deflate",
            "User-Agent": USER_AGENT,
            "token": md5_hex(timestamp + SECRET),
            "tokenparam": f"{timestamp},{version}",
        },
        timeout=15,
    )
    content_encoding = response.headers.get("content-encoding", "identity")
    print(f"  {path}: HTTP {response.status_code}, encoding={content_encoding}")
    response.raise_for_status()
    outer = parse_outer_json(response.text)
    if outer.get("code") != 200 or not outer.get("data"):
        raise RuntimeError(f"API code={outer.get('code')}, data={bool(outer.get('data'))}")
    return decrypt_data(str(outer["data"]), timestamp)


def query_domain(domain: str, album_id: str) -> str:
    session = Session(impersonate="chrome")
    setting = request_encrypted(session, domain, "/setting", DEFAULT_APP_VERSION)
    version = str(setting.get("jm3_version") or DEFAULT_APP_VERSION)
    print(f"  jm3_version: {version}")
    album = request_encrypted(session, domain, "/album", version, {"id": album_id})
    title = str(album.get("name") or "").strip()
    if not title:
        raise LookupError("详情缺少 name")
    return title


def main() -> int:
    parser = argparse.ArgumentParser(description="独立诊断 JM 号查询接口")
    parser.add_argument("jm_id", help="例如 350234、JM350234 或 album 链接")
    args = parser.parse_args()
    album_id = parse_jm_id(args.jm_id)

    print(f"查询 JM{album_id}")
    for domain in API_DOMAINS:
        print(f"[{domain}]")
        try:
            title = query_domain(domain, album_id)
        except Exception as exc:
            print(f"  失败: {type(exc).__name__}: {exc}")
            continue
        print(f"  成功: JM{album_id}: {title}")
        return 0

    print("全部候选域名均失败")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
