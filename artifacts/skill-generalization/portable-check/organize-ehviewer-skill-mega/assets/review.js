'use strict';
(() => {
const config=JSON.parse(document.querySelector('#tool-data').textContent), model=config.model, catalog=config.catalog;
const clone=x=>JSON.parse(JSON.stringify(x)), esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const categories=['main','extra','collection','remaster','other'], labels={main:'正篇',extra:'番外',collection:'合集',remaster:'重制版',other:'其他'};
const source=new Map(catalog.items.map(x=>[x.gid,x])), original=new Map(catalog.items.map(x=>[x.gid,x.originalPosition]));
let operations=[], cursor=0, selected=new Set(), editing=null, state;
const $=id=>document.getElementById(id), stateKeys=['decisions','gidOrder','seriesOrder','direction'];
state=initial();
function initial(){return Object.fromEntries(stateKeys.map(k=>[k,clone(model[k])]));}
function aligned(s){s.gidOrder=s.decisions.map(x=>x.gid);s.seriesOrder=[...new Set(s.decisions.map(x=>x.canonicalSeriesId))];return s;}
function compare(a,b){for(let i=0;i<a.length;i++){if(a[i]<b[i])return -1;if(a[i]>b[i])return 1;}return 0;}
function sorted(rows,direction){
 if(!['ascending','descending'].includes(direction))throw Error('排序方向无效');
 const groups=new Map();for(const r of rows){if(!groups.has(r.canonicalSeriesId))groups.set(r.canonicalSeriesId,[]);groups.get(r.canonicalSeriesId).push(r);}
 const output=[];const ids=[...groups.keys()].sort((a,b)=>Math.min(...groups.get(a).map(r=>original.get(r.gid)))-Math.min(...groups.get(b).map(r=>original.get(r.gid))));
 for(const sid of ids){const rs=groups.get(sid), anchors=new Map();for(const r of rs){const k=JSON.stringify([r.category,r.branch]);anchors.set(k,Math.min(anchors.get(k)??Infinity,original.get(r.gid)));}
 const key=r=>{const known=r.orderReliable&&r.orderConfidence>=.85&&r.position!==null;let pos=['volume','chapter','part','rangeEnd'].map(k=>r.position?.[k]??-1);if(direction==='descending')pos=pos.map(x=>-x);return [categories.indexOf(r.category),anchors.get(JSON.stringify([r.category,r.branch])),known?0:1,...(known?pos:[0,0,0,0]),original.get(r.gid)];};
 output.push(...rs.sort((a,b)=>compare(key(a),key(b))));}
 return aligned({decisions:output,direction});
}
function valid(s){
 if(s.decisions.length!==catalog.items.length||new Set(s.decisions.map(r=>r.gid)).size!==catalog.items.length||s.decisions.some(r=>!source.has(r.gid)))throw Error('GID 覆盖不完整');
 const names=new Map();for(const r of s.decisions){if(!r.canonicalSeriesId?.trim()||!r.canonicalSeriesTitle?.trim()||!r.branch?.trim()||!categories.includes(r.category))throw Error('分类字段无效');
 if(names.has(r.canonicalSeriesId)&&names.get(r.canonicalSeriesId)!==r.canonicalSeriesTitle)throw Error('系列名称不一致');names.set(r.canonicalSeriesId,r.canonicalSeriesTitle);
 for(const k of ['confidence','orderConfidence'])if(!Number.isFinite(r[k])||r[k]<0||r[k]>1)throw Error('置信度无效');
 if(r.position!==null){if(Object.keys(r.position).sort().join(',')!=='chapter,part,rangeEnd,volume')throw Error('位置字段无效');if(Object.values(r.position).every(x=>x===null)||Object.values(r.position).some(x=>x!==null&&(!Number.isFinite(x)||x<0)))throw Error('编号必须非负或留空');const start=r.position.chapter??r.position.volume??r.position.part;if(r.position.rangeEnd!==null&&(start==null||r.position.rangeEnd<start))throw Error('合集范围结束位置小于开始位置');}
 if(r.orderReliable&&(r.position===null||r.orderConfidence<.85))throw Error('可靠排序需要明确位置和足够依据');}
 return s;
}
function apply(s,op){
 const by=new Map(s.decisions.map(x=>[x.gid,x]));
 if(op.type==='edit'){if(!by.has(op.gid))throw Error('GID 无效');Object.assign(by.get(op.gid),clone(op.patch));s=sorted(s.decisions,s.direction);}
 else if(op.type==='assign'){if(!op.seriesId?.trim()||!op.title?.trim()||!op.gids.length)throw Error('请选择漫画及目标系列');for(const r of s.decisions){if(op.gids.includes(r.gid)){r.canonicalSeriesId=op.seriesId;r.needsHumanReview=true;r.evidence.push({kind:'human',claim:'人工调整系列归属。'});}if(r.canonicalSeriesId===op.seriesId)r.canonicalSeriesTitle=op.title;}s=sorted(s.decisions,s.direction);}
 else if(op.type==='acknowledge'){for(const g of op.gids){if(!by.has(g))throw Error('GID 无效');by.get(g).needsHumanReview=false;by.get(g).evidence.push({kind:'human',claim:'人工确认当前分类和位置；未知关系保持独立。'});}}
 else if(op.type==='rename'){for(const r of s.decisions)if(r.canonicalSeriesId===op.seriesId)r.canonicalSeriesTitle=op.title;}
 else if(op.type==='direction')s=sorted(s.decisions,op.value);
 else if(op.type==='moveItem'||op.type==='pin'){if(!by.has(op.gid))throw Error('GID 无效');const gids=s.gidOrder.filter(x=>x!==op.gid),before=op.type==='pin'?(gids[0]??null):op.before;if(before!==null&&!gids.includes(before))throw Error('移动目标无效');gids.splice(before===null?gids.length:gids.indexOf(before),0,op.gid);s.decisions=gids.map(x=>by.get(x));}
 else if(op.type==='moveSeries'){const moved=s.decisions.filter(x=>x.canonicalSeriesId===op.seriesId),other=s.decisions.filter(x=>x.canonicalSeriesId!==op.seriesId),i=op.before===null?other.length:other.findIndex(x=>x.canonicalSeriesId===op.before);if(!moved.length||i<0)throw Error('系列移动目标无效');s.decisions=[...other.slice(0,i),...moved,...other.slice(i)];}
 else throw Error('未知审核操作');return valid(aligned(s));
}
function replay(ops){let s=initial();for(const op of ops)s=apply(s,op);return s;}
function perform(op){try{const next=apply(clone(state),op);operations=operations.slice(0,cursor);operations.push(op);cursor++;state=next;$('confirm').checked=false;render();notice('已保存操作，可撤销。');}catch(e){notice(e.message,true);}}
function notice(message,error=false){$('message').textContent=message;$('message').className=error?'error':'';}
function evidenceHtml(r){return r.evidence.map(e=>{const text=esc(e.claim);return e.kind==='web'&&/^https?:\/\//.test(e.url)?`<li><a href="${esc(e.url)}" target="_blank" rel="noopener noreferrer">${text}</a> · ${esc(e.accessedAt)}</li>`:`<li>${text}</li>`;}).join('');}
function rowHtml(r){const item=source.get(r.gid),pos=r.position?Object.entries(r.position).filter(([k,v])=>v!==null).map(([k,v])=>`${({volume:'卷',chapter:'章',part:'篇内位置',rangeEnd:'范围至'})[k]} ${v}`).join(' / '):'位置未知';return `<article class="item${r.needsHumanReview?' pending':''}" data-gid="${r.gid}" draggable="true"><div class="row"><input type="checkbox" data-select="${r.gid}" aria-label="选择 GID ${r.gid}" ${selected.has(r.gid)?'checked':''}><div class="item-title"><b>${esc(item.title||item.titleJpn||'无标题')}</b>${item.titleJpn&&item.titleJpn!==item.title?`<div>${esc(item.titleJpn)}</div>`:''}<div class="meta">GID ${r.gid} · ${labels[r.category]} / ${esc(r.branch)} · ${esc(pos)} · ${r.orderReliable?'可靠排序':'保留待定位置'} · 置信度 ${r.confidence.toFixed(2)}</div></div><button data-action="edit" data-gid="${r.gid}">编辑</button></div><p>${r.needsHumanReview?'<strong class="badge">待审</strong> ':''}${esc(r.reason)}</p>${r.candidateSeries.length?`<p>候选：${r.candidateSeries.map(x=>`${esc(x.seriesId)} — ${esc(x.reason)}`).join('；')}</p>`:''}<details class="evidence"><summary>依据 ${r.evidence.length} 条</summary><ul>${evidenceHtml(r)}</ul></details><div class="item-actions"><button data-action="up" data-gid="${r.gid}">上移</button><button data-action="down" data-gid="${r.gid}">下移</button><button data-action="pin" data-gid="${r.gid}">单本置顶</button><button data-action="ack" data-gid="${r.gid}">确认保留现状</button></div></article>`;}
function groupRowsHtml(rows){let prior=null,out='';for(const r of rows){const key=JSON.stringify([r.category,r.branch,r.orderReliable]);if(key!==prior){out+=`<h3 class="subgroup-title">${labels[r.category]} · ${esc(r.branch==='main'?'主线':r.branch)}${r.orderReliable?'':' · 位置待定'}</h3>`;prior=key;}out+=rowHtml(r);}return out;}
function render(){
 const active=document.activeElement,restoreFocus=active?.dataset?.action?{...active.dataset}:null;
 const groups=[];for(const r of state.decisions){if(!groups.length||groups.at(-1).sid!==r.canonicalSeriesId)groups.push({sid:r.canonicalSeriesId,rows:[]});groups.at(-1).rows.push(r);}
 $('groups').innerHTML=groups.map(g=>`<details class="group" data-series="${esc(g.sid)}" ${g.rows.some(r=>r.needsHumanReview)?'open':''}><summary><span>${esc(g.rows[0].canonicalSeriesTitle)}</span> <small>${g.rows.length} 本</small></summary><div class="series-actions"><button data-action="selectSeries" data-series="${esc(g.sid)}">选择整个系列</button><button data-action="seriesUp" data-series="${esc(g.sid)}">系列上移</button><button data-action="seriesDown" data-series="${esc(g.sid)}">系列下移</button></div>${groupRowsHtml(g.rows)}</details>`).join('');
 $('count').textContent=`${state.decisions.length} 本 · ${state.seriesOrder.length} 个系列/独立条目 · ${state.decisions.filter(r=>r.needsHumanReview).length} 本待审`;
 $('direction').value=state.direction;$('undo').disabled=cursor===0;$('redo').disabled=cursor===operations.length;
 const prior=$('target').value;$('target').innerHTML=state.seriesOrder.map(s=>`<option value="${esc(s)}">${esc(state.decisions.find(r=>r.canonicalSeriesId===s).canonicalSeriesTitle)} (${esc(s)})</option>`).join('');if(state.seriesOrder.includes(prior))$('target').value=prior;
 $('selected-count').textContent=selected.size;$('confirm').disabled=state.decisions.some(r=>r.needsHumanReview);filter();
 if(restoreFocus){const selector='button[data-action="'+CSS.escape(restoreFocus.action)+'"]'+(restoreFocus.gid?'[data-gid="'+restoreFocus.gid+'"]':'[data-series="'+CSS.escape(restoreFocus.series)+'"]');document.querySelector(selector)?.focus({preventScroll:true});}
}
function filter(){const q=$('search').value.toLocaleLowerCase(),pending=$('pending-only').checked;let visible=0;const by=new Map(state.decisions.map(r=>[r.gid,r]));document.querySelectorAll('.item').forEach(el=>{const r=by.get(Number(el.dataset.gid)),i=source.get(r.gid);el.hidden=Boolean((pending&&!r.needsHumanReview)||(q&&!`${r.gid} ${i.title} ${i.titleJpn} ${r.canonicalSeriesTitle}`.toLocaleLowerCase().includes(q)));if(!el.hidden)visible++;});document.querySelectorAll('.group').forEach(g=>{g.hidden=![...g.querySelectorAll('.item')].some(el=>!el.hidden);if(q&&!g.hidden)g.open=true;});$('visible').textContent=visible;}
function openEditor(gid){editing=gid;const r=state.decisions.find(x=>x.gid===gid);$('edit-name').textContent=`编辑 GID ${gid}`;$('category').value=r.category;$('branch').value=r.branch;for(const k of ['volume','chapter','part','rangeEnd'])$(k).value=r.position?.[k]??'';$('reliable').checked=r.orderReliable;$('reason').value=r.reason;$('editor').hidden=false;$('category').focus();}
$('groups').addEventListener('change',e=>{if(e.target.dataset.select){const gid=Number(e.target.dataset.select);if(e.target.checked)selected.add(gid);else selected.delete(gid);$('selected-count').textContent=selected.size;}});
$('groups').addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;const a=b.dataset.action,gid=Number(b.dataset.gid),sid=b.dataset.series;
 if(a==='edit')openEditor(gid);else if(a==='pin')perform({type:'pin',gid});else if(a==='ack')perform({type:'acknowledge',gids:[gid]});
 else if(a==='up'||a==='down'){const i=state.gidOrder.indexOf(gid);if(a==='up'&&i>0)perform({type:'moveItem',gid,before:state.gidOrder[i-1]});if(a==='down'&&i<state.gidOrder.length-1)perform({type:'moveItem',gid,before:state.gidOrder[i+2]??null});}
 else if(a==='selectSeries'){for(const r of state.decisions)if(r.canonicalSeriesId===sid)selected.add(r.gid);render();}
 else if(a==='seriesUp'||a==='seriesDown'){const i=state.seriesOrder.indexOf(sid);if(a==='seriesUp'&&i>0)perform({type:'moveSeries',seriesId:sid,before:state.seriesOrder[i-1]});if(a==='seriesDown'&&i<state.seriesOrder.length-1)perform({type:'moveSeries',seriesId:sid,before:state.seriesOrder[i+2]??null});}
});
let dragged=null;$('groups').addEventListener('dragstart',e=>{const row=e.target.closest('.item');if(row){dragged=Number(row.dataset.gid);e.dataTransfer.setData('text/plain',String(dragged));}});$('groups').addEventListener('dragover',e=>{if(dragged&&e.target.closest('.item'))e.preventDefault();});$('groups').addEventListener('drop',e=>{const row=e.target.closest('.item');if(dragged&&row){e.preventDefault();const before=Number(row.dataset.gid);if(before!==dragged)perform({type:'moveItem',gid:dragged,before});}dragged=null;});$('groups').addEventListener('dragend',()=>dragged=null);
$('save-edit').onclick=()=>{if(editing===null)return;const values=Object.fromEntries(['volume','chapter','part','rangeEnd'].map(k=>[k,$(k).value===''?null:Number($(k).value)]));const position=Object.values(values).every(x=>x===null)?null:values,r=state.decisions.find(x=>x.gid===editing);perform({type:'edit',gid:editing,patch:{category:$('category').value,branch:$('branch').value,position,orderReliable:$('reliable').checked,orderConfidence:$('reliable').checked?1:0,reason:$('reason').value,needsHumanReview:true,evidence:[...r.evidence,{kind:'human',claim:'人工修改分类属性或卷章位置。'}]}});};
$('assign').onclick=()=>{const seriesId=$('target').value,r=state.decisions.find(x=>x.canonicalSeriesId===seriesId);if(r)perform({type:'assign',gids:[...selected],seriesId,title:r.canonicalSeriesTitle});};
$('split').onclick=()=>{const title=$('new-title').value.trim();if(!title)return notice('请填写新系列名称。',true);let i=1;while(state.seriesOrder.includes(`manual:${i}`))i++;perform({type:'assign',gids:[...selected],seriesId:`manual:${i}`,title});};
$('rename').onclick=()=>perform({type:'rename',seriesId:$('target').value,title:$('new-title').value.trim()});
$('ack-selected').onclick=()=>perform({type:'acknowledge',gids:[...selected]});
$('select-visible').onclick=()=>{document.querySelectorAll('.item:not([hidden])').forEach(x=>selected.add(Number(x.dataset.gid)));render();};
$('clear-selected').onclick=()=>{selected.clear();render();};
$('direction').onchange=()=>perform({type:'direction',value:$('direction').value});
$('undo').onclick=()=>{if(cursor){cursor--;state=replay(operations.slice(0,cursor));$('confirm').checked=false;render();}};
$('redo').onclick=()=>{if(cursor<operations.length){cursor++;state=replay(operations.slice(0,cursor));$('confirm').checked=false;render();}};
$('reset').onclick=()=>{if(window.confirm('恢复最初模型结果？已保存的 JSON 文件不会被删除。')){operations=[];cursor=0;selected.clear();state=initial();$('confirm').checked=false;render();}};
$('search').oninput=filter;$('pending-only').onchange=filter;
function payload(confirmed){if(confirmed&&(!$('confirm').checked||state.decisions.some(r=>r.needsHumanReview)))throw Error('请逐项或选择条目确认保留现状，再勾选最终确认。');return {formatVersion:3,snapshotFingerprint:catalog.snapshotFingerprint,metadataFingerprint:catalog.metadataFingerprint,auditTrail:clone(model.auditTrail),baseModelDigest:config.modelHash,reviewStatus:confirmed?'confirmed':'draft',operations:clone(operations.slice(0,cursor)),...clone(state)};}
function save(confirmed){try{const data=payload(confirmed),a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)+'\n'],{type:'application/json'}));a.download=confirmed?'ehviewer-confirmed-v3.json':'ehviewer-review-draft-v3.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);notice(confirmed?'已导出确认结果；写回前仍须通过命令行校验。':'已保存审核进度。');}catch(e){notice(e.message,true);}}
$('save-draft').onclick=()=>save(false);$('export').onclick=()=>save(true);
const normalized=x=>Array.isArray(x)?x.map(normalized):x&&typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,normalized(x[k])])):x;
$('import').onchange=async e=>{try{const file=e.target.files[0];if(!file)return;const value=JSON.parse(await file.text());if(value.formatVersion!==3||value.baseModelDigest!==config.modelHash||value.metadataFingerprint!==catalog.metadataFingerprint||value.snapshotFingerprint!==catalog.snapshotFingerprint||JSON.stringify(normalized(value.auditTrail))!==JSON.stringify(normalized(model.auditTrail)))throw Error('该进度不属于当前模型结果与书库');const next=replay(value.operations);const imported=Object.fromEntries(stateKeys.map(k=>[k,value[k]]));if(JSON.stringify(normalized(next))!==JSON.stringify(normalized(imported)))throw Error('修改记录不能重现导入内容');operations=clone(value.operations);cursor=operations.length;state=next;selected.clear();$('confirm').checked=false;render();notice('审核进度已恢复，最终确认需要重新勾选。');}catch(err){notice(err.message,true);}e.target.value='';};
// No hidden network requests, storage, or external libraries. JSON files are the durable saved state.
render();
})();
