/* Optional real-browser QA. Requires an existing Playwright installation and Chromium/Edge. */
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),{spawnSync}=require('node:child_process'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const out=path.resolve(process.argv[2]),python=process.argv[3]||'python',scripts=path.resolve(__dirname,'../scripts');
const runId=Date.now();
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 const context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
 const page=await context.newPage(),errors=[],requests=[];
 page.on('pageerror',e=>(errors.push(e.message),console.error('PAGE ERROR:',e.message)));page.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
 async function download(button,name){const pending=page.waitForEvent('download');await page.locator(button).click();const d=await pending,file=path.join(out,`${runId}-${name}.json`);await d.saveAs(file);return file;}
 function validate(file,confirmed=false,prefix='review'){
  const args=['-B',path.join(scripts,'validate_order.py'),'--catalog',path.join(out,`${prefix}-catalog.json`),'--model',path.join(out,`${prefix}-model.json`),'--order',file];if(confirmed)args.push('--confirmed');
  const r=spawnSync(python,args,{encoding:'utf8',env:{...process.env,PYTHONUTF8:'1',PYTHONDONTWRITEBYTECODE:'1'}});assert.equal(r.status,0,r.stderr);
 }
 const start=Date.now();await page.goto(pathToFileURL(path.join(out,'review.html')).href);await page.locator('#count').filter({hasText:'12'}).waitFor();
 assert.equal(await page.locator('.item').count(),12);
 await page.locator('[data-select="1"]').check();await page.locator('#new-title').fill('Split test series');await page.locator('#split').click();
 assert.equal(await page.locator('[data-series="manual:1"] .item').count(),1);
 await page.locator('#target').selectOption('series:1');await page.locator('#assign').click();
 assert.equal(await page.locator('[data-series="manual:1"]').count(),0);
 await page.locator('#new-title').fill('Renamed test series');await page.locator('#rename').click();
 await page.locator('#undo').focus();await page.keyboard.press('Enter');assert.equal(await page.locator('.group>summary').filter({hasText:'Renamed test series'}).count(),0);
 await page.locator('#redo').focus();await page.keyboard.press('Enter');assert.equal(await page.locator('.group>summary').filter({hasText:'Renamed test series'}).count(),1);
 await page.locator('[data-action="edit"][data-gid="1"]').click();await page.locator('#category').selectOption('main');await page.locator('#chapter').fill('0');await page.locator('#reliable').check();await page.locator('#reason').fill('Browser fixture: explicitly verified chapter zero.');await page.locator('#save-edit').click();
 await page.locator('#direction').selectOption('descending');await page.locator('#direction').selectOption('ascending');
 await page.locator('[data-action="pin"][data-gid="3"]').click();
 await page.locator('#search').fill('1');const draft=await download('#save-draft','draft');validate(draft);
 const draftData=JSON.parse(fs.readFileSync(draft));assert.equal(draftData.gidOrder.length,12);assert.equal(draftData.gidOrder[0],3);
 await page.reload();await page.locator('#count').filter({hasText:'12'}).waitFor();await page.locator('#import').setInputFiles(draft);await page.locator('#message').filter({hasText:'审核进度已恢复'}).waitFor();
 const restored=await download('#save-draft','restored');assert.deepEqual(JSON.parse(fs.readFileSync(restored)),draftData);validate(restored);
 const tampered=path.join(out,`${runId}-tampered.json`),bad=structuredClone(draftData);bad.decisions[0].reason='Unlogged change';fs.writeFileSync(tampered,JSON.stringify(bad));await page.locator('#import').setInputFiles(tampered);await page.locator('#message.error').waitFor();
 const badMode=path.join(out,`${runId}-bad-mode.json`);fs.writeFileSync(badMode,JSON.stringify({...draftData,auditMode:'deep'}));await page.locator('#import').setInputFiles(badMode);await page.locator('#message.error').waitFor();
 await page.locator('#search').fill('');await page.locator('#select-visible').click();await page.locator('#ack-selected').click();await page.locator('#confirm').check();
 const confirmed=await download('#export','confirmed');validate(confirmed,true);
 await page.evaluate(()=>{document.querySelectorAll('.group').forEach(x=>x.open=true);scrollTo(0,0);});await page.screenshot({path:path.join(out,'review-desktop.png')});
 await page.setViewportSize({width:390,height:844});await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:path.join(out,'review-mobile.png')});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'mobile horizontal overflow');
 const smallMs=Date.now()-start;await page.setViewportSize({width:1440,height:1000});
 const largeStart=Date.now();await page.goto(pathToFileURL(path.join(out,'large-review.html')).href);await page.locator('#count').filter({hasText:'3184'}).waitFor();assert.equal(await page.locator('.item').count(),3184);const largeLoadMs=Date.now()-largeStart;
 await page.locator('#search').fill('Chapter 3184');assert.equal(await page.locator('.item:not([hidden])').count(),1);
 const largeDraft=await download('#save-draft','large-draft');assert.equal(JSON.parse(fs.readFileSync(largeDraft)).gidOrder.length,3184);validate(largeDraft,false,'large-review');
 await page.locator('#search').fill('');await page.locator('#select-visible').click();await page.locator('#ack-selected').click();await page.locator('#confirm').check();const largeConfirmed=await download('#export','large-confirmed');validate(largeConfirmed,true,'large-review');
 assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
 const report={status:'passed',browser:'Edge headless',smallItemCount:12,largeItemCount:3184,largeLoadMs,smallFlowMs:smallMs,
  checked:['split','merge','rename','edit-category-and-zero-position','direction','pin','undo','redo','filtered-complete-export','save-import-replay','tampered-import-rejected','audit-mode-tampering-rejected','human-confirmation','backend-validation','mobile-width','keyboard-undo-redo','large-library'],consoleErrors:errors,networkRequests:requests,confirmedExport:confirmed,largeConfirmedExport:largeConfirmed};
 fs.writeFileSync(path.join(out,'browser-report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
