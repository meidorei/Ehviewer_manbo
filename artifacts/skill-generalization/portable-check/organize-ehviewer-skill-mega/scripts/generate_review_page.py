#!/usr/bin/env python3
"""Self-contained review document; all original metadata remains readable without JS."""
import argparse
import html
import json
from pathlib import Path
from common import read_json, validate_order, sha256_file, require


def generate(catalog, model, model_hash):
    validate_order(catalog, model)
    esc=lambda x:html.escape(str(x or ''),quote=True)
    items={x['gid']:x for x in catalog['items']}
    static=[]
    for r in model['decisions']:
        item=items[r['gid']]
        static.append('<article class="item"><b>'+esc(item['title'] or item['titleJpn'])+'</b><p>'+esc(item['titleJpn'])+'</p><p>GID '+str(r['gid'])+' · '+esc(r['canonicalSeriesTitle'])+' · '+('待审' if r['needsHumanReview'] else '已审核')+'</p><p>'+esc(r['reason'])+'</p></article>')
    assets=Path(__file__).resolve().parents[1]/'assets'
    css=(assets/'review.css').read_text(encoding='utf-8-sig')
    js=(assets/'review.js').read_text(encoding='utf-8-sig')
    data=json.dumps(dict(catalog=catalog,model=model,modelHash=model_hash),ensure_ascii=False,allow_nan=False).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
    return '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>EhViewer 漫画系列校对台</title><style>'''+css+'''</style></head><body>
<header><h1>漫画系列校对台</h1><p>从第一章开始 · 原标题完整保留 · 分类与排序都可修正</p><div id="count" class="summary-line"></div>
<div class="toolbar"><label>搜索 <input id="search" type="search" placeholder="标题、系列或 GID"></label><label><input id="pending-only" type="checkbox"> 只看待审</label><span>显示 <b id="visible"></b> 本</span><label>组内方向 <select id="direction"><option value="ascending">从第一章开始</option><option value="descending">最新章节在前</option></select></label></div>
<div class="toolbar"><button id="undo">撤销</button><button id="redo">重做</button><button id="reset">恢复模型结果</button><button id="save-draft">保存审核进度</button><label>导入进度 <input id="import" type="file" accept=".json,application/json"></label></div></header>
<main><section><noscript><p>以下完整清单无需 JavaScript 即可阅读；启用 JavaScript 后可以编辑和导出。</p></noscript><div id="groups">'''+''.join(static)+'''</div></section>
<aside><h2>调整分类</h2><p>已选 <b id="selected-count">0</b> 本</p><div class="buttons"><button id="select-visible">选择当前显示</button><button id="clear-selected">清空选择</button></div>
<label>目标系列<select id="target"></select></label><button id="assign">将所选移入目标系列</button><p class="note">选择整个系列后移入另一系列即为合并。拆分时选择需要独立出来的成员。</p>
<label>新系列名称 / 重命名<input id="new-title" type="text"></label><div class="buttons"><button id="split">将所选拆为新系列</button><button id="rename">重命名目标系列</button></div>
<button id="ack-selected">确认所选保持现状</button><p class="note">无法确定的关系可以保留独立或未知位置后确认。编辑属性或归属后会重新按规则排列；手动移动与单本置顶会记录为排序例外。</p>
<fieldset id="editor" hidden><legend id="edit-name">编辑条目</legend><label>类别<select id="category"><option value="main">正篇</option><option value="extra">番外</option><option value="collection">合集</option><option value="remaster">重制版</option><option value="other">其他</option></select></label><label>故事分支<input id="branch" value="main"></label><div class="positions"><label>卷<input id="volume" type="number" min="0" step="any"></label><label>章<input id="chapter" type="number" min="0" step="any"></label><label>篇内位置<input id="part" type="number" min="0" step="any"></label><label>范围结束<input id="rangeEnd" type="number" min="0" step="any"></label></div><label><input id="reliable" type="checkbox"> 位置有可靠依据</label><label>理由<textarea id="reason"></textarea></label><button id="save-edit">保存条目修改</button></fieldset>
<hr><label><input id="confirm" type="checkbox"> 已检查全部条目，确认此分类与完整顺序</label><button id="export" class="primary">导出确认结果 JSON</button><p id="message" role="status" aria-live="polite"></p><p class="note">导出不操作手机。写回前，工具会验证原模型摘要、全部修改记录、GID 与标题指纹。</p></aside></main>
<script id="tool-data" type="application/json">'''+data+'''</script><script>'''+js+'''</script></body></html>'''


def main():
    p=argparse.ArgumentParser(description='Generate a static, self-contained v3 classification and ordering editor.')
    for arg in ('catalog','order','output'):p.add_argument('--'+arg,required=True,type=Path)
    a=p.parse_args();require(not a.output.exists(),'refusing to overwrite review HTML')
    page=generate(read_json(a.catalog),read_json(a.order),sha256_file(a.order))
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(page,encoding='utf-8')
    print(json.dumps(dict(output=str(a.output),bytes=a.output.stat().st_size),ensure_ascii=False))
if __name__=='__main__':main()
