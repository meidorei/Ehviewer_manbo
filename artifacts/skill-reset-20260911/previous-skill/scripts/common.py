"""Shared v3 contracts, deterministic ordering and replayable review operations."""
import copy
import hashlib
import json
import math
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

VERSION = 3
MAX_GID = 9007199254740991  # lossless in the offline JavaScript editor
CATEGORIES = ('main', 'extra', 'collection', 'remaster', 'other')
ROUNDS = ('candidate', 'author', 'unresolved', 'global')
AUDIT_MODES = ('lightweight', 'deep')
POSITION_FIELDS = ('volume', 'chapter', 'part', 'rangeEnd')
DECISION_FIELDS = {'gid', 'canonicalSeriesId', 'canonicalSeriesTitle', 'category', 'branch',
                   'position', 'orderReliable', 'orderConfidence', 'confidence', 'reason',
                   'needsHumanReview', 'candidateSeries', 'evidence'}
EDIT_FIELDS = DECISION_FIELDS - {'gid', 'canonicalSeriesId', 'canonicalSeriesTitle'}


class ContractError(ValueError, RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ContractError(message)


def reject_constant(value):
    raise ValueError('non-finite JSON number: ' + value)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key: ' + key)
        result[key] = value
    return result


def finite_float(raw):
    value = float(raw)
    require(math.isfinite(value), 'non-finite JSON number: ' + raw)
    return value


def loads(raw):
    return json.loads(raw, object_pairs_hook=unique_pairs, parse_constant=reject_constant, parse_float=finite_float)


def read_json(path):
    return loads(Path(path).read_text(encoding='utf-8-sig'))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def integer(value):
    return type(value) is int and 0 < value <= MAX_GID


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def fingerprints(items):
    ordered = sorted(items, key=lambda x: x['gid'])
    gids = hashlib.sha256('\n'.join(str(x['gid']) for x in ordered).encode()).hexdigest()
    metadata = digest([{k: x.get(k) for k in ('gid', 'title', 'titleJpn')} for x in ordered])
    return gids, metadata


def make_catalog(items, source=None):
    rows = []
    for index, item in enumerate(items, 1):
        rows.append({k: item.get(k, index if k == 'originalPosition' else None)
                     for k in ('gid', 'originalPosition', 'title', 'titleJpn')})
    # Validate before sorting/hashing, to provide a useful error for malformed IDs.
    validate_items(rows)
    gid_hash, metadata_hash = fingerprints(rows)
    result = dict(formatVersion=3, snapshotFingerprint=gid_hash, metadataFingerprint=metadata_hash,
                  items=rows, source=source or {'kind': 'json', 'writebackSupported': False})
    validate_catalog(result)
    return result


def validate_items(items):
    require(isinstance(items, list), 'catalog items must be a list')
    gids, positions = set(), set()
    for row in items:
        require(isinstance(row, dict), 'catalog item must be an object')
        require(integer(row.get('gid')) and row['gid'] not in gids, 'catalog GIDs must be unique safe positive integers')
        require(integer(row.get('originalPosition')) and row['originalPosition'] not in positions,
                'originalPosition must be unique positive integers')
        for key in ('title', 'titleJpn'):
            require(key in row and (row[key] is None or isinstance(row[key], str)), key + ' must be string or null')
        gids.add(row['gid']); positions.add(row['originalPosition'])


def validate_catalog(catalog):
    require(isinstance(catalog, dict) and type(catalog.get('formatVersion')) is int and catalog['formatVersion'] == 3,
            'v3 catalog required; explicitly migrate older catalogs')
    validate_items(catalog.get('items'))
    gid_hash, metadata_hash = fingerprints(catalog['items'])
    require(catalog.get('snapshotFingerprint') == gid_hash, 'catalog GID fingerprint mismatch')
    require(catalog.get('metadataFingerprint') == metadata_hash, 'catalog title metadata fingerprint mismatch')


def validate_decisions(catalog, rows):
    validate_catalog(catalog)
    require(isinstance(rows, list), 'decisions must be a list')
    gids = set(); names = {}
    for row in rows:
        require(isinstance(row, dict) and set(row) == DECISION_FIELDS, 'decision fields must match v3 contract')
        gid = row['gid']
        require(integer(gid) and gid not in gids, 'decision GIDs must be unique safe integers')
        gids.add(gid)
        for key in ('canonicalSeriesId', 'canonicalSeriesTitle', 'branch', 'reason'):
            require(nonempty(row[key]), f'GID {gid}: {key} must be a non-empty string')
        sid = row['canonicalSeriesId']
        require(names.get(sid, row['canonicalSeriesTitle']) == row['canonicalSeriesTitle'], 'inconsistent series title: ' + sid)
        names[sid] = row['canonicalSeriesTitle']
        require(row['category'] in CATEGORIES, f'GID {gid}: invalid category')
        for key in ('confidence', 'orderConfidence'):
            require(number(row[key]) and 0 <= row[key] <= 1, f'GID {gid}: invalid {key}')
        for key in ('orderReliable', 'needsHumanReview'):
            require(type(row[key]) is bool, f'GID {gid}: {key} must be boolean')
        pos = row['position']
        require(pos is None or (isinstance(pos, dict) and set(pos) == set(POSITION_FIELDS)), 'invalid structured position')
        if pos is not None:
            require(any(v is not None for v in pos.values()), 'empty position must be null')
            require(all(v is None or (number(v) and v >= 0) for v in pos.values()), 'position must contain finite nonnegative numbers or null')
            if pos['rangeEnd'] is not None:
                start = next((pos[k] for k in ('chapter', 'volume', 'part') if pos[k] is not None), None)
                require(start is not None and pos['rangeEnd'] >= start, 'invalid collection range')
        if row['orderReliable']:
            require(pos is not None and row['orderConfidence'] >= .85, 'reliable order needs a position and orderConfidence >= 0.85')
        require(isinstance(row['candidateSeries'], list), 'candidateSeries must be a list')
        for candidate in row['candidateSeries']:
            require(isinstance(candidate, dict) and set(candidate) == {'seriesId', 'reason'} and all(nonempty(v) for v in candidate.values()), 'invalid candidate series hint')
        require(isinstance(row['evidence'], list) and row['evidence'], 'decision requires inspectable evidence')
        for evidence in row['evidence']:
            require(isinstance(evidence, dict) and evidence.get('kind') in ('title', 'web', 'history', 'human') and nonempty(evidence.get('claim')), 'invalid evidence')
            if evidence['kind'] == 'web':
                url = urlsplit(evidence.get('url', ''))
                require(url.scheme in ('http', 'https') and url.hostname and not url.username and not url.password, 'evidence URL must be public HTTP(S) without credentials')
                require(evidence.get('sourceType') == 'page', 'search snippets are not evidence')
                try:
                    datetime.fromisoformat(evidence.get('accessedAt', '').replace('Z', '+00:00'))
                except (ValueError, TypeError):
                    raise ValueError('web evidence requires ISO accessedAt')
    require(gids == {x['gid'] for x in catalog['items']}, 'decisions must cover every catalog GID exactly once')


def singleton(item):
    return dict(gid=item['gid'], canonicalSeriesId=f"item:{item['gid']}",
                canonicalSeriesTitle=item.get('title') or item.get('titleJpn') or f"GID {item['gid']}",
                category='other', branch='main', position=None, orderReliable=False, orderConfidence=0,
                confidence=0, reason='尚未确认系列关系，独立保留。', needsHumanReview=True,
                candidateSeries=[], evidence=[{'kind': 'title', 'claim': '待审核原始标题。'}])


def decision_digest(rows):
    return digest(sorted(rows, key=lambda row: row['gid']))


def envelope(catalog, rows, trail=None):
    return dict(formatVersion=3, snapshotFingerprint=catalog['snapshotFingerprint'],
                metadataFingerprint=catalog['metadataFingerprint'], decisions=rows, auditTrail=trail or [])


def validate_binding(catalog, value):
    require(type(value.get('formatVersion')) is int and value['formatVersion'] == 3, 'v3 decisions required; explicitly migrate legacy files')
    for key in ('snapshotFingerprint', 'metadataFingerprint'):
        require(value.get(key) == catalog[key], key + ' mismatch')
    validate_decisions(catalog, value.get('decisions'))


def audit_mode(value):
    # Existing v3 files without a mode retain the original four-round contract.
    mode = value.get('auditMode', 'deep')
    require(mode in AUDIT_MODES, 'invalid auditMode')
    return mode


def next_round(value):
    if audit_mode(value) == 'lightweight':
        return 'focused' if value['auditTrail'] else 'semantic'
    index = len(value['auditTrail'])
    require(index < len(ROUNDS), 'all deep rounds already completed')
    return ROUNDS[index]


def validate_audits(catalog, value, complete=True):
    trail = value.get('auditTrail')
    require(isinstance(trail, list), 'auditTrail must be a list')
    mode = audit_mode(value)
    if mode == 'deep':
        require(len(trail) == 4 if complete else len(trail) <= 4, 'four completed deep semantic audit rounds required')
    elif complete:
        require(bool(trail), 'one complete semantic audit required')
    previous = None
    all_gids = {x['gid'] for x in catalog['items']}
    for i, entry in enumerate(trail):
        expected = ROUNDS[i] if mode == 'deep' else ('semantic' if i == 0 else 'focused')
        require(isinstance(entry, dict) and entry.get('round') == expected, 'audit round missing or out of order')
        require(entry.get('metadataFingerprint') == catalog['metadataFingerprint'], 'audit metadata mismatch')
        reviewed = entry.get('reviewedGids')
        require(isinstance(reviewed, list) and all(integer(x) for x in reviewed) and len(reviewed) == len(set(reviewed)), 'invalid audit coverage')
        if expected == 'focused':
            require(bool(reviewed) and set(reviewed) <= all_gids, 'focused audit needs valid selected GIDs')
            changed = entry.get('changedGids')
            require(isinstance(changed, list) and all(integer(x) for x in changed) and len(changed) == len(set(changed))
                    and set(changed) <= set(reviewed), 'focused changes outside reviewed GIDs')
        else:
            require(set(reviewed) == all_gids, 'audit coverage incomplete')
        require(entry.get('conflicts') == [], 'unresolved audit conflicts')
        for key in ('inputDecisionsDigest', 'outputDecisionsDigest', 'inputArtifactDigest'):
            require(isinstance(entry.get(key), str) and len(entry[key]) == 64 and all(c in '0123456789abcdef' for c in entry[key]), 'invalid audit digest')
        require(nonempty(entry.get('summary')), 'audit summary missing')
        if previous:
            require(entry['inputDecisionsDigest'] == previous, 'audit chain mismatch')
        previous = entry['outputDecisionsDigest']
    if trail:
        require(previous == decision_digest(value['decisions']), 'decisions changed after audit')


def stable_order(catalog, rows, direction='ascending'):
    validate_decisions(catalog, rows)
    require(direction in ('ascending', 'descending'), 'invalid direction')
    original = {x['gid']: x['originalPosition'] for x in catalog['items']}
    groups = {}
    for row in rows:
        groups.setdefault(row['canonicalSeriesId'], []).append(row)
    ordered = []
    for sid in sorted(groups, key=lambda s: min(original[x['gid']] for x in groups[s])):
        branch_anchors = {}
        for row in groups[sid]:
            key = (row['category'], row['branch'])
            branch_anchors[key] = min(branch_anchors.get(key, original[row['gid']]), original[row['gid']])
        def key(row):
            known = row['orderReliable'] and row['orderConfidence'] >= .85 and row['position'] is not None
            pos = row['position'] or {}
            # Missing dimensions are distinct from chapter/volume zero; ranges use their start then end.
            values = tuple((-1 if pos.get(k) is None else pos[k]) for k in POSITION_FIELDS)
            if direction == 'descending': values = tuple(-v for v in values)
            return (CATEGORIES.index(row['category']), branch_anchors[(row['category'], row['branch'])],
                    0 if known else 1, values if known else (0, 0, 0, 0), original[row['gid']])
        ordered.extend(sorted(groups[sid], key=key))
    return {'decisions': ordered, 'gidOrder': [x['gid'] for x in ordered],
            'seriesOrder': list(dict.fromkeys(x['canonicalSeriesId'] for x in ordered)), 'direction': direction}


def state_of(value):
    return copy.deepcopy({k: value[k] for k in ('decisions', 'gidOrder', 'seriesOrder', 'direction')})


def validate_state(catalog, value, automatic=False):
    validate_decisions(catalog, value.get('decisions'))
    gids = value.get('gidOrder'); series = value.get('seriesOrder')
    require(isinstance(gids, list) and all(integer(x) for x in gids), 'gidOrder must contain safe integer GIDs')
    require(gids == [x['gid'] for x in value['decisions']], 'decisions and gidOrder must align')
    require(series == list(dict.fromkeys(x['canonicalSeriesId'] for x in value['decisions'])), 'seriesOrder must match first occurrence order')
    require(value.get('direction') in ('ascending', 'descending'), 'invalid direction')
    if automatic:
        require(state_of(value) == stable_order(catalog, value['decisions'], value['direction']), 'model order violates series/category/branch/position ordering')


def replay(catalog, model, operations):
    state = state_of(model)
    require(isinstance(operations, list), 'operations must be a list')
    for op in operations:
        require(isinstance(op, dict), 'operation must be an object')
        kind = op.get('type'); by_gid = {x['gid']: x for x in state['decisions']}
        if kind == 'edit':
            require(set(op) == {'type', 'gid', 'patch'} and integer(op['gid']) and op['gid'] in by_gid, 'invalid edit GID')
            require(isinstance(op['patch'], dict) and op['patch'] and set(op['patch']) <= EDIT_FIELDS, 'invalid edit fields')
            by_gid[op['gid']].update(copy.deepcopy(op['patch']))
            state = stable_order(catalog, state['decisions'], state['direction'])
        elif kind == 'assign':
            require(set(op) == {'type', 'gids', 'seriesId', 'title'}, 'invalid assign fields')
            require(isinstance(op['gids'], list) and op['gids'] and all(integer(g) and g in by_gid for g in op['gids']) and len(op['gids']) == len(set(op['gids'])), 'invalid assigned GIDs')
            require(nonempty(op['seriesId']) and nonempty(op['title']), 'assignment requires series ID and title')
            for row in state['decisions']:
                if row['gid'] in op['gids']:
                    row['canonicalSeriesId'] = op['seriesId']
                    row['needsHumanReview'] = True
                    row['evidence'].append({'kind': 'human', 'claim': '人工调整系列归属。'})
                if row['canonicalSeriesId'] == op['seriesId']:
                    row['canonicalSeriesTitle'] = op['title']
            state = stable_order(catalog, state['decisions'], state['direction'])
        elif kind == 'acknowledge':
            require(set(op) == {'type', 'gids'} and isinstance(op['gids'], list) and all(integer(g) and g in by_gid for g in op['gids']) and len(op['gids']) == len(set(op['gids'])), 'invalid acknowledgment GIDs')
            for gid in op['gids']:
                by_gid[gid]['needsHumanReview'] = False
                by_gid[gid]['evidence'].append({'kind': 'human', 'claim': '人工确认当前分类和位置；未知关系保持独立。'})
        elif kind == 'rename':
            require(set(op) == {'type', 'seriesId', 'title'} and op['seriesId'] in state['seriesOrder'] and nonempty(op['title']), 'invalid rename')
            for row in state['decisions']:
                if row['canonicalSeriesId'] == op['seriesId']: row['canonicalSeriesTitle'] = op['title']
        elif kind == 'direction':
            require(set(op) == {'type', 'value'}, 'invalid direction operation')
            state = stable_order(catalog, state['decisions'], op['value'])
        elif kind in ('moveItem', 'pin'):
            require(set(op) == ({'type', 'gid', 'before'} if kind == 'moveItem' else {'type', 'gid'}), 'invalid move fields')
            require(integer(op['gid']) and op['gid'] in by_gid, 'invalid moved GID')
            gids = [g for g in state['gidOrder'] if g != op['gid']]
            before = gids[0] if kind == 'pin' and gids else op.get('before')
            require(before is None or (integer(before) and before in gids), 'invalid move target')
            gids.insert(len(gids) if before is None else gids.index(before), op['gid'])
            state['decisions'] = [by_gid[g] for g in gids]
        elif kind == 'moveSeries':
            require(set(op) == {'type', 'seriesId', 'before'} and op['seriesId'] in state['seriesOrder'], 'invalid series move')
            moved = [x for x in state['decisions'] if x['canonicalSeriesId'] == op['seriesId']]
            other = [x for x in state['decisions'] if x['canonicalSeriesId'] != op['seriesId']]
            require(op['before'] is None or any(x['canonicalSeriesId'] == op['before'] for x in other), 'invalid series move target')
            index = len(other) if op['before'] is None else next(i for i,x in enumerate(other) if x['canonicalSeriesId'] == op['before'])
            state['decisions'] = other[:index] + moved + other[index:]
        else:
            raise ValueError('unsupported review operation: ' + str(kind))
        state['gidOrder'] = [x['gid'] for x in state['decisions']]
        state['seriesOrder'] = list(dict.fromkeys(x['canonicalSeriesId'] for x in state['decisions']))
        validate_state(catalog, state)
    return state


def validate_order(catalog, order, model=None, model_hash=None, confirmed=False):
    validate_binding(catalog, order)
    if model is None:
        require(order.get('reviewStatus') == 'model-reviewed', 'model order required')
        validate_audits(catalog, order)
        validate_state(catalog, order, automatic=True)
    else:
        validate_order(catalog, model)
        require(order.get('baseModelDigest') == model_hash, 'review base model digest mismatch')
        require(order.get('auditTrail') == model.get('auditTrail'), 'review changed audit trail')
        require(audit_mode(order) == audit_mode(model), 'review changed audit mode')
        require(order.get('reviewStatus') in ('draft', 'confirmed'), 'invalid review status')
        expected = replay(catalog, model, order.get('operations'))
        require(state_of(order) == expected, 'review operations do not reproduce exported decisions/order')
        validate_state(catalog, order)
        if confirmed:
            require(order.get('reviewStatus') == 'confirmed', 'human-confirmed export required')
            require(not any(x['needsHumanReview'] for x in order['decisions']), 'review still contains unacknowledged items')
