'use strict';
const base=JSON.parse(document.querySelector('#data').textContent),clone=x=>JSON.parse(JSON.stringify(x)),esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let rows=clone(base.items),selected=new Set(),history=[];
const $=id=>document.getElementById(id),say=x=>$('message').textContent=x;
function saveState(){history.push(clone(rows));$('confirm').checked=false;}
const kinds={main:'正篇',extra:'番外',collection:'合集',remaster:'重制版',other:'其他'};
function render(){let position=0,groups=[];for(const r of rows){if(!groups.length||groups.at(-1).id!==r.seriesId)groups.push({id:r.seriesId,name:r.series,items:[]});groups.at(-1).items.push(r);}
$('groups').innerHTML=groups.map(g=>`<section><h2>${esc(g.name)} <small>${g.items.length} 本</small></h2>${g.items.map(r=>`<article data-gid="${r.gid}"><label><input type="checkbox" data-select="${r.gid}" ${selected.has(r.gid)?'checked':''}> <span class="position" title="当前阅读顺序">${++position}</span><b>${esc(r.title||r.titleJpn)}</b></label><p>${esc(r.titleJpn)}</p><p>GID ${r.gid} · ${kinds[r.category]||esc(r.category)} · ${esc(r.branch==='main'?'主线':r.branch)} · 章号 ${r.order??'未标注'}${r.end!=null?'–'+r.end:''}</p><p>${esc(r.note)}</p><div class="buttons"><button data-move="up" data-gid="${r.gid}">上移</button><button data-move="down" data-gid="${r.gid}">下移</button><button data-edit="${r.gid}">修改章号标注</button></div></article>`).join('')}</section>`).join('');
$('count').textContent=`${rows.length} 本 · ${new Set(rows.map(r=>r.seriesId)).size} 个系列/独立条目`;$('undo').disabled=!history.length;selectionStatus();filter();}
function selectionStatus(){
$('selection-count').textContent='已选 '+selected.size+' 本';
$('clear').disabled=$('assign').disabled=$('independent').disabled=!selected.size;
for(const el of document.querySelectorAll('article[data-gid]'))el.classList.toggle('is-selected',selected.has(Number(el.dataset.gid)));
}
function filter(){const q=$('search').value.trim().toLowerCase(),mode=$('view').value,by=new Map(rows.map(r=>[r.gid,r])),sizes=new Map();let visible=0;
for(const r of rows)sizes.set(r.seriesId,(sizes.get(r.seriesId)||0)+1);
for(const el of document.querySelectorAll('article[data-gid]')){const r=by.get(Number(el.dataset.gid));const match=!q||[r.gid,r.title,r.titleJpn,r.series].join(' ').toLowerCase().includes(q);
const scope=mode==='all'||mode==='series'&&sizes.get(r.seriesId)>1||mode==='single'&&sizes.get(r.seriesId)===1||mode==='unknown'&&r.order==null||mode==='selected'&&selected.has(r.gid);
el.hidden=!(match&&scope);if(!el.hidden)visible++;}
for(const el of document.querySelectorAll('#groups section'))el.hidden=![...el.querySelectorAll('article')].some(a=>!a.hidden);
$('visible-count').textContent='显示 '+visible+' / '+rows.length+' 本';$('empty').hidden=visible!==0;$('select-visible').disabled=!visible;}
$('search').oninput=filter;$('view').onchange=filter;
$('groups').onchange=e=>{if(e.target.dataset.select){const g=Number(e.target.dataset.select);e.target.checked?selected.add(g):selected.delete(g);selectionStatus();filter();}};
$('select-visible').onclick=()=>{document.querySelectorAll('article[data-gid]:not([hidden])').forEach(el=>selected.add(Number(el.dataset.gid)));render();};$('clear').onclick=()=>{selected.clear();render();};
$('assign').onclick=()=>{const name=$('series-name').value.trim();if(!name||!selected.size)return say('填写系列名称并选择条目。');saveState();let id=rows.find(r=>r.series===name)?.seriesId||'manual:'+Date.now();for(const r of rows)if(selected.has(r.gid)){r.seriesId=id;r.series=name;}const members=rows.filter(r=>r.seriesId===id),at=rows.findIndex(r=>r.seriesId===id),other=rows.filter(r=>r.seriesId!==id);other.splice(at,0,...members);rows=other;render();};
$('independent').onclick=()=>{if(!selected.size)return;saveState();for(const r of rows)if(selected.has(r.gid)){r.seriesId='item:'+r.gid;r.series=r.title||r.titleJpn||String(r.gid);}render();};
$('groups').onclick=e=>{const b=e.target.closest('button');if(!b)return;if(b.dataset.move){const i=rows.findIndex(r=>r.gid===Number(b.dataset.gid)),j=i+(b.dataset.move==='up'?-1:1);if(j>=0&&j<rows.length){saveState();[rows[i],rows[j]]=[rows[j],rows[i]];render();}}else if(b.dataset.edit){const r=rows.find(r=>r.gid===Number(b.dataset.edit)),value=prompt('位置编号；留空表示未知',r.order??'');if(value===null)return;const n=value.trim()===''?null:Number(value);if(n!==null&&(!Number.isFinite(n)||n<0))return say('请输入非负编号或留空。');if(r.end!==null&&(n===null||n>r.end))return say('合集开始位置不能大于结束位置。');saveState();r.order=n;render();}};
$('undo').onclick=()=>{if(history.length){rows=history.pop();$('confirm').checked=false;render();}};
function payload(confirmed){return {...base,confirmed,items:clone(rows),gidOrder:rows.map(r=>r.gid)};}
function download(confirmed){if(confirmed&&!$('confirm').checked)return say('请先确认整个结果。');const blob=new Blob([JSON.stringify(payload(confirmed),null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=confirmed?'confirmed.json':'draft.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);say('已导出；没有操作手机。');}
$('draft').onclick=()=>download(false);$('export').onclick=()=>download(true);
$('import').onchange=async e=>{try{const p=JSON.parse(await e.target.files[0].text()),by=new Map(base.items.map(r=>[r.gid,r]));if(p.formatVersion!==1||p.catalogHash!==base.catalogHash||!Array.isArray(p.items)||p.items.length!==rows.length||new Set(p.items.map(r=>r.gid)).size!==rows.length||JSON.stringify(p.gidOrder)!==JSON.stringify(p.items.map(r=>r.gid)))throw Error('进度与书库不匹配');const names=new Map();for(const r of p.items){const old=by.get(r.gid);if(!old||r.title!==old.title||r.titleJpn!==old.titleJpn||typeof r.seriesId!=='string'||!r.seriesId.trim()||typeof r.series!=='string'||!r.series.trim()||typeof r.branch!=='string'||!r.branch.trim()||!['main','extra','collection','remaster','other'].includes(r.category)||(r.order!==null&&(!Number.isFinite(r.order)||r.order<0))||(r.end!==null&&(!Number.isFinite(r.end)||r.order===null||r.end<r.order))||(names.has(r.seriesId)&&names.get(r.seriesId)!==r.series))throw Error('进度字段或原题不匹配');names.set(r.seriesId,r.series);}saveState();rows=clone(p.items);selected.clear();render();say('进度已恢复，请重新确认最终结果。');}catch(err){say(err.message);}e.target.value='';};render();
