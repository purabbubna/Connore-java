/* Controller tests use in-memory API and DOM stubs; no student database is touched. */
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const decode = text => String(text).replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
function attributes(text) {
  const attrs = {};
  for (const match of text.matchAll(/([\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) attrs[match[1]] = decode(match[2] ?? match[3] ?? match[4] ?? '');
  return attrs;
}
function node(tag = 'div', attrs = {}, content = '') {
  const result = {
    tagName: tag.toUpperCase(), id: attrs.id || '', name: attrs.name || '', type: attrs.type || '',
    value: attrs.value || '', checked: Object.hasOwn(attrs, 'checked'), disabled: Object.hasOwn(attrs, 'disabled'),
    isConnected: true, dataset: {}, attributes: {...attrs}, innerHTML: content, textContent: decode(content.replace(/<[^>]*>/g, '')),
    setAttribute(key, value) { this.attributes[key] = String(value); }, removeAttribute(key) { delete this.attributes[key]; },
    getAttribute(key) { return this.attributes[key] ?? null; }, focus() {}, scrollIntoView() {},
    closest(selector) { return selector === '[data-action]' && this.dataset.action ? this : selector === 'form' ? this.form || null : null; },
    querySelector(selector) { return selector.includes('submit') ? this.submitButton : null; },
    querySelectorAll() { return []; }, reset() { for (const field of Object.values(this.elements || {})) if (field?.tagName) field.value = ''; }
  };
  for (const [key, value] of Object.entries(attrs)) if (key.startsWith('data-')) result.dataset[key.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
  if (tag === 'textarea') result.value = decode(content);
  if (tag === 'select') {
    const options = [...content.matchAll(/<option\b([^>]*)>([\s\S]*?)<\/option>/g)].map(match => attributes(match[1]));
    result.value = (options.find(option => Object.hasOwn(option, 'selected')) || options[0] || {}).value || '';
  }
  return result;
}
function contentAfter(markup, start, tag) {
  const remainder = markup.slice(start), tokens = new RegExp(`<(/?)${tag}\\b[^>]*>`, 'gi');
  let depth = 1, match;
  while ((match = tokens.exec(remainder))) {
    depth += match[1] ? -1 : 1;
    if (!depth) return remainder.slice(0, match.index);
  }
  return '';
}
function parse(markup) {
  const nodes = {};
  for (const match of markup.matchAll(/<([a-z][\w-]*)\b([^>]*)>/gi)) {
    const [, tag, raw] = match, attrs = attributes(raw);
    if (!attrs.id) continue;
    nodes[attrs.id] = node(tag.toLowerCase(), attrs, contentAfter(markup, match.index + match[0].length, tag));
  }
  for (const form of Object.values(nodes).filter(value => value.tagName === 'FORM')) {
    form.elements = {};
    for (const match of form.innerHTML.matchAll(/<(input|select|textarea)\b([^>]*)>/gi)) {
      const attrs = attributes(match[2]);
      if (!attrs.name) continue;
      const field = attrs.id && nodes[attrs.id] || node(match[1], attrs, contentAfter(form.innerHTML, match.index + match[0].length, match[1]));
      field.form = form; form.elements[attrs.name] = field;
    }
    form.elements.namedItem = name => form.elements[name];
    form.submitButton = node('button', {type: 'submit'});
  }
  return nodes;
}

const photo = {interestId: 11, name: 'Photography'}, java = {interestId: 12, name: 'Java Programming'};
const catalog = [{categoryId: 1, name: 'Creative & technical', interests: [photo, java]}];
const member = {studentId: 42, fullName: 'Test Student', bio: 'A shared hobby.', sharedInterests: [photo], interests: [photo], reason: 'Photography'};
const community = {communityId: 21, interestId: 11, name: 'Photography', category: 'Creative & technical', description: 'Meet photographers.', memberCount: 4, joined: false};
const connection = {studentId: 43, connectionId: 31, fullName: 'Test Connection', bio: '', interests: [java], status: 'ACCEPTED', socialLinks: [{platform: 'GitHub', handle: 'test-handle', shared: true}]};

async function setup({signedIn = false, hasProfile = false, collegeDomain = 'sitpune.edu.in', responses = {}, profile = {}, initialHash = ''} = {}) {
  const listeners = {}, events = {}, requests = [], failures = [], pending = [];
  let appNodes = {}, pageNodes = {}, appMarkup = '', pageMarkup = '', hash = initialHash;
  const app = node(), page = node();
  Object.defineProperty(app, 'innerHTML', {get: () => appMarkup, set(value) { appMarkup = value; appNodes = parse(value); pageNodes = {}; pageMarkup = ''; }});
  Object.defineProperty(page, 'innerHTML', {get: () => pageMarkup, set(value) { pageMarkup = value; pageNodes = parse(value); }});
  const getElementById = id => id === 'app' ? app : id === 'page' && appMarkup.includes('id="page"') ? page : pageNodes[id] || appNodes[id] || null;
  const document = {getElementById, addEventListener: (type, handler) => { listeners[type] = handler; }, querySelector: selector => selector[0] === '#' ? getElementById(selector.slice(1)) : null, querySelectorAll: () => [], activeElement: null};
  let savedProfile = {studentId: 41, fullName: 'Test Account', department: '', yearOfStudy: null, bio: '', showDepartment: false, showYear: false, suggestible: true, ...profile};
  let selected = [11], links = [{linkId: 71, platform: 'GitHub', handle: 'student-handle', shared: false}], groups = [structuredClone(community)];
  const location = {pathname: '/', search: '', get hash() { return hash; }, set hash(value) { hash = value && !value.startsWith('#') ? '#' + value : value; }};
  const context = {
    document, location, history: {replaceState(_state, _title, value) { const index = value.indexOf('#'); hash = index < 0 ? '' : value.slice(index); }},
    URL, URLSearchParams, console, setTimeout, clearTimeout, confirm: () => true, scrollTo() {},
    addEventListener: (type, handler) => { events[type] = handler; },
    FormData: class { constructor(form) { this.form = form; } get(name) { const field = this.form.elements[name]; return field && (!['checkbox', 'radio'].includes(field.type) || field.checked) ? field.value || (field.checked ? 'on' : '') : null; } *[Symbol.iterator]() { for (const [name, field] of Object.entries(this.form.elements)) if (field?.tagName && (!['checkbox', 'radio'].includes(field.type) || field.checked)) yield [name, field.value || (field.checked ? 'on' : '')]; } },
    fetch: async (path, options) => {
      const method = options.method, body = options.body ? JSON.parse(options.body) : undefined;
      const request = {path, method, body, options}; requests.push(request);
      const blocked = pending.find(item => item.path === path && item.method === method && (!item.once || !item.used));
      if (blocked) {
        blocked.used = true;
        await blocked.promise;
        if (Object.hasOwn(blocked, 'data')) return reply(blocked.status || 200, blocked.data);
      }
      const failureIndex = failures.findIndex(item => item.path === path && (!item.method || item.method === method));
      if (failureIndex >= 0) { const failure = failures.splice(failureIndex, 1)[0]; if (failure.network) throw new TypeError('Network unavailable'); return reply(failure.status || 400, {error: failure.error || 'REQUEST_FAILED'}); }
      if (Object.hasOwn(responses, path)) return reply(200, responses[path]);
      if (path === '/api/session') return reply(signedIn ? 200 : 401, signedIn ? {studentId: 41, hasProfile} : {error: 'UNAUTHENTICATED'});
      if (path === '/api/auth/register') return reply(201, {accountId: 41});
      if (path === '/api/auth/login') { signedIn = true; return reply(200, {studentId: 41, hasProfile}); }
      if (path === '/api/auth/logout') { signedIn = false; return reply(204, null); }
      if (path === '/api/profile') { if (method === 'PUT') { savedProfile = {...savedProfile, ...body, fullName: body.fullName.trim()}; hasProfile = true; } return hasProfile ? reply(200, savedProfile) : reply(404, {error: 'PROFILE_REQUIRED'}); }
      if (path === '/api/profile/privacy') { savedProfile = {...savedProfile, ...body}; return reply(200, {}); }
      if (path === '/api/interests') return reply(200, catalog);
      if (path === '/api/profile/interests') { if (method === 'POST') selected = [...new Set([...selected, body.interestId])]; return reply(200, method === 'GET' ? selected : {}); }
      if (path.startsWith('/api/profile/interests/')) { selected = selected.filter(id => id !== Number(path.split('/').pop())); return reply(204, null); }
      if (path === '/api/profile/social-links') { if (method === 'POST') { const link = {linkId: 72, ...body, shared: false}; links.push(link); return reply(201, link); } return reply(200, links); }
      if (path.startsWith('/api/profile/social-links/')) { links.find(link => link.linkId === Number(path.split('/').pop())).shared = body.shared; return reply(200, {}); }
      if (path === '/api/matches?limit=30') return reply(200, [member]);
      if (path === '/api/communities') return reply(200, groups);
      if (/^\/api\/communities\/\d+\/members$/.test(path)) return reply(200, [member]);
      if (/^\/api\/communities\/\d+\/(join|membership)$/.test(path)) { groups[0].joined = method === 'POST'; return reply(method === 'POST' ? 200 : 204, method === 'POST' ? {} : null); }
      if (path.startsWith('/api/connections?view=')) return reply(200, [{...connection, status: path.endsWith('accepted') ? 'ACCEPTED' : 'PENDING'}]);
      if (path === '/api/connections') return reply(200, {result: 'PENDING', connectionId: 99});
      if (/^\/api\/connections\/\d+\/(respond|block)$/.test(path)) return reply(200, {});
      throw new Error('Unhandled test API route: ' + method + ' ' + path);
    }
  };
  function reply(status, data) { return {status, ok: status < 400, json: async () => structuredClone(data)}; }
  context.window = context; vm.createContext(context);
  for (const name of ['config', 'api', 'app']) { vm.runInContext(fs.readFileSync(`dist/js/${name}.js`, 'utf8'), context, {filename: name + '.js'}); if (name === 'config') context.CONNORE_CONFIG.collegeDomain = collegeDomain; }
  const settle = async () => { for (let index = 0; index < 8; index++) await new Promise(resolve => setImmediate(resolve)); };
  await settle();
  async function dispatch(type, target) { assert.equal(typeof listeners[type], 'function', type + ' handler exists'); await listeners[type]({target, preventDefault() {}}); await settle(); }
  return {
    context, requests, app, page, element: getElementById, settle,
    get markup() { return app.innerHTML + page.innerHTML; },
    get status() { return getElementById('status')?.textContent || ''; }, get error() { return getElementById('error')?.textContent || ''; },
    async go(route) { location.hash = '#' + route; await events.hashchange(); await settle(); },
    async submit(id, values = {}) { const form = getElementById(id); assert.ok(form, id + ' is rendered'); for (const [key, value] of Object.entries(values)) { const field = form.elements[key]; assert.ok(field, key + ' field exists'); if (typeof value === 'boolean') field.checked = value; else field.value = String(value); } await dispatch('submit', form); },
    async click(action, id, extra = {}) { const button = node('button', {'data-action': action, 'data-id': String(id || '')}); Object.assign(button.dataset, extra); await dispatch('click', button); return button; },
    async changeInterest(id, checked) { const checkbox = node('input', {type: 'checkbox', 'data-interest-id': String(id)}); checkbox.checked = checked; await dispatch('change', checkbox); return checkbox; },
    async share(id, checked) { const checkbox = node('input', {type: 'checkbox', 'data-link-id': String(id)}); checkbox.checked = checked; await dispatch('change', checkbox); return checkbox; },
    async input(id, value) { const input = getElementById(id); assert.ok(input, id + ' is rendered'); input.value = value; await dispatch('input', input); },
    fail(path, options = {}) { failures.push({path, ...options}); },
    hold(method, path) { let release; const promise = new Promise(resolve => { release = resolve; }); const item = {method, path, promise}; pending.push(item); return () => { pending.splice(pending.indexOf(item), 1); release(); }; },
    holdNext(method, path, data, status = 200) { let release; const promise = new Promise(resolve => { release = resolve; }); const item = {method, path, promise, data, status, once: true}; pending.push(item); return () => { pending.splice(pending.indexOf(item), 1); release(); }; },
    startClick(action, id) { const button = node('button', {'data-action': action, 'data-id': String(id)}); return {button, promise: dispatch('click', button)}; }
  };
}

test('anonymous visitors get login and registration with the configured college domain', async () => {
  const ui = await setup({collegeDomain: 'campus.example.edu'});
  assert.equal(ui.context.location.hash, '#login');
  assert.match(ui.markup, /id="auth-form"/); assert.match(ui.markup, /campus\.example\.edu/);
  assert.ok(!ui.requests.some(request => request.path === '/api/profile'));
  await ui.go('register'); assert.match(ui.markup, /autocomplete="new-password"/);
  assert.doesNotMatch(ui.markup, /demo|animation|motion-toggle|journey-step|dashboard/i);
});

test('registration submits only the supplied college account fields', async () => {
  const ui = await setup(); await ui.go('register');
  await ui.submit('auth-form', {email: 'new-student@sitpune.edu.in', password: 'test-password'});
  const registration = ui.requests.find(request => request.path === '/api/auth/register');
  assert.equal(registration.method, 'POST');
  assert.deepEqual(registration.body, {email: 'new-student@sitpune.edu.in', password: 'test-password'});
  assert.ok(!ui.requests.some(request => request.path === '/api/profile' && request.method === 'PUT'));
  assert.doesNotMatch(ui.markup, /test-password/);
});

test('login sends credentials only to the authentication endpoint and signs out cleanly', async () => {
  const ui = await setup({hasProfile: true});
  await ui.submit('auth-form', {email: 'student@sitpune.edu.in', password: 'test-password'});
  assert.equal(ui.context.location.hash, '#people');
  const login = ui.requests.find(request => request.path === '/api/auth/login');
  assert.deepEqual(login.body, {email: 'student@sitpune.edu.in', password: 'test-password'});
  assert.equal(login.options.credentials, 'same-origin'); assert.equal(login.options.headers['X-Connore-Request'], '1');
  assert.doesNotMatch(ui.markup, /test-password/);
  await ui.click('logout'); assert.equal(ui.context.location.hash, '#login');
  assert.ok(ui.requests.some(request => request.path === '/api/auth/logout' && request.method === 'POST'));
});

test('incorrect credentials stay on login with a useful message and can be retried', async () => {
  const ui = await setup({hasProfile: true});
  ui.fail('/api/auth/login', {method: 'POST', status: 401, error: 'BAD_CREDENTIALS'});
  await ui.submit('auth-form', {email: 'student@sitpune.edu.in', password: 'test-password'});
  assert.equal(ui.context.location.hash, '#login'); assert.match(ui.error, /incorrect/i);
  assert.equal(ui.element('auth-form').submitButton.disabled, false);
  await ui.submit('auth-form', {email: 'student@sitpune.edu.in', password: 'test-password'});
  assert.equal(ui.context.location.hash, '#people');
});

test('new students save a real profile before selecting interests or browsing people', async () => {
  const ui = await setup({signedIn: true});
  assert.equal(ui.context.location.hash, '#profile'); assert.match(ui.markup, /id="profile-form"/);
  assert.doesNotMatch(ui.element('interest-list').innerHTML, / checked/);
  assert.ok(!ui.requests.some(request => request.path.startsWith('/api/matches')));
  await ui.submit('profile-form', {fullName: '  First Student  ', department: 'Computing', yearOfStudy: '2', bio: 'A short introduction.'});
  const saved = ui.requests.find(request => request.path === '/api/profile' && request.method === 'PUT');
  assert.equal(saved.body.yearOfStudy, 2); assert.equal(saved.body.bio, 'A short introduction.');
  assert.equal(ui.element('profile-form').elements.fullName.value, 'First Student');
  await ui.go('people'); assert.match(ui.markup, /Test Student/);
});

test('profile settings update separate endpoints without silently saving an introduction draft', async () => {
  const ui = await setup({signedIn: true, hasProfile: true}); await ui.go('profile');
  ui.element('profile-form').elements.bio.value = 'Unsaved introduction.';
  await ui.changeInterest(12, true);
  assert.ok(ui.requests.some(request => request.path === '/api/profile/interests' && request.method === 'POST' && request.body.interestId === 12));
  assert.equal(ui.element('profile-form').elements.bio.value, 'Unsaved introduction.');
  await ui.changeInterest(11, false);
  assert.ok(ui.requests.some(request => request.path === '/api/profile/interests/11' && request.method === 'DELETE'));
  await ui.submit('privacy-form', {suggestible: true, showDepartment: true, showYear: false});
  assert.deepEqual(ui.requests.find(request => request.path === '/api/profile/privacy').body, {suggestible: true, showDepartment: true, showYear: false});
  assert.ok(!ui.requests.some(request => request.path === '/api/profile' && request.method === 'PUT'));
});

test('social handles require explicit sharing', async () => {
  const ui = await setup({signedIn: true, hasProfile: true}); await ui.go('profile');
  await ui.submit('social-form', {platform: 'LinkedIn', handle: 'student-name'});
  assert.deepEqual(ui.requests.find(request => request.path === '/api/profile/social-links' && request.method === 'POST').body, {platform: 'LinkedIn', handle: 'student-name'});
  await ui.share(71, true);
  assert.deepEqual(ui.requests.find(request => request.path === '/api/profile/social-links/71' && request.method === 'PUT').body, {shared: true});
});

test('a failed interest update restores its checkbox and keeps the introduction draft', async () => {
  const ui = await setup({signedIn: true, hasProfile: true}); await ui.go('profile');
  ui.element('profile-form').elements.bio.value = 'Unsaved introduction.';
  ui.fail('/api/profile/interests', {method: 'POST', network: true});
  const checkbox = await ui.changeInterest(12, true);
  assert.equal(checkbox.checked, false); assert.equal(checkbox.disabled, false); assert.ok(ui.error);
  assert.equal(ui.element('profile-form').elements.bio.value, 'Unsaved introduction.');
  await ui.changeInterest(12, true); assert.match(ui.status, /saved/i);
});

test('search filters existing people, communities and interests without extra API calls', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  let requestCount = ui.requests.length;
  await ui.input('people-search', 'no matching student'); assert.match(ui.element('people-list').innerHTML, /No people match/);
  await ui.input('people-search', 'photography'); assert.match(ui.element('people-list').innerHTML, /Test Student/);
  assert.equal(ui.requests.length, requestCount);
  await ui.go('communities'); requestCount = ui.requests.length;
  await ui.input('community-search', 'no matching group'); assert.match(ui.element('community-list').innerHTML, /No communities match/);
  await ui.input('community-search', 'photo'); assert.match(ui.element('community-list').innerHTML, /Photography/);
  assert.equal(ui.requests.length, requestCount);
  await ui.go('profile'); requestCount = ui.requests.length;
  await ui.input('interest-search', 'java'); assert.match(ui.element('interest-list').innerHTML, /Java Programming/);
  assert.doesNotMatch(ui.element('interest-list').innerHTML, /Photography/);
  await ui.input('interest-search', ''); assert.match(ui.element('interest-list').innerHTML, /Photography/);
  assert.equal(ui.requests.length, requestCount);
});

test('people and community members can send interest-based connection requests', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  assert.match(ui.markup, /Test Student/);
  await ui.click('connect', 42, {interest: '11'});
  assert.deepEqual(ui.requests.find(request => request.path === '/api/connections' && request.method === 'POST').body, {toStudentId: 42, interestId: 11});
  await ui.go('communities'); assert.match(ui.markup, /Photography/);
  await ui.click('join', 21); assert.ok(ui.requests.some(request => request.path === '/api/communities/21/join' && request.method === 'POST'));
  await ui.go('community/21'); assert.match(ui.markup, /Test Student/);
  await ui.go('communities'); await ui.click('leave', 21);
  assert.ok(ui.requests.some(request => request.path === '/api/communities/21/membership' && request.method === 'DELETE'));
});

test('requests expose accept, decline, block and opted-in connection handles', async () => {
  const ui = await setup({signedIn: true, hasProfile: true}); await ui.go('requests');
  assert.match(ui.markup, /test-handle/);
  await ui.click('accept', 31); await ui.click('decline', 31); await ui.click('block', 31);
  const responses = ui.requests.filter(request => request.path === '/api/connections/31/respond');
  assert.deepEqual(responses.map(request => request.body), [{accept: true}, {accept: false}]);
  assert.ok(ui.requests.some(request => request.path === '/api/connections/31/block' && request.method === 'POST'));
});

test('closed requests show a neutral status and let the sender request again', async () => {
  const closed = {...member, connectionId: 32, status: 'CLOSED', contextInterestId: 11};
  const ui = await setup({signedIn: true, hasProfile: true, responses: {
    '/api/connections?view=incoming': [], '/api/connections?view=accepted': [],
    '/api/connections?view=outgoing': [closed]
  }});
  assert.match(ui.element('people-list').innerHTML, /Send request/);
  assert.doesNotMatch(ui.element('people-list').innerHTML, /Request sent/);
  await ui.go('requests');
  assert.match(ui.page.innerHTML, /Request closed/);
  assert.doesNotMatch(ui.page.innerHTML, /Awaiting reply|data-action="block"|declined|expired/i);
  assert.match(ui.page.innerHTML, /data-action="connect" data-id="42" data-interest="11"/);
  await ui.click('connect', 42, {interest: '11'});
  assert.deepEqual(ui.requests.find(request => request.path === '/api/connections' && request.method === 'POST').body, {toStudentId: 42, interestId: 11});
});

test('requesting again drops an inactive historical context interest', async () => {
  const ui = await setup({signedIn: true, hasProfile: true, responses: {
    '/api/connections?view=incoming': [], '/api/connections?view=accepted': [],
    '/api/connections?view=outgoing': [{...member, connectionId: 32, status: 'CLOSED', contextInterestId: 999}]
  }});
  await ui.go('requests');
  assert.match(ui.page.innerHTML, /data-action="connect" data-id="42" data-interest=""/);
  await ui.click('connect', 42);
  assert.deepEqual(ui.requests.find(request => request.path === '/api/connections' && request.method === 'POST').body, {toStudentId: 42, interestId: null});
});

test('visiting Requests fetches a request sent by another account', async () => {
  const responses = {'/api/connections?view=incoming': [], '/api/connections?view=outgoing': [], '/api/connections?view=accepted': []};
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  responses['/api/connections?view=incoming'] = [{...member, connectionId: 33, status: 'PENDING'}];
  await ui.go('requests');
  assert.match(ui.page.innerHTML, /Test Student/);
  assert.match(ui.page.innerHTML, /data-action="accept" data-id="33"/);
});

test('navigation shows external acceptance and removes a revoked shared handle', async () => {
  const responses = {
    '/api/connections?view=incoming': [{...member, connectionId: 33, status: 'PENDING'}],
    '/api/connections?view=outgoing': [], '/api/connections?view=accepted': []
  };
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  responses['/api/connections?view=incoming'] = [];
  responses['/api/connections?view=accepted'] = [{...connection, socialLinks: [{platform: 'GitHub', handle: 'revocable-handle'}]}];
  await ui.go('requests');
  assert.match(ui.page.innerHTML, /revocable-handle/);
  assert.doesNotMatch(ui.page.innerHTML, /data-action="accept"/);
  responses['/api/connections?view=accepted'] = [{...connection, socialLinks: []}];
  await ui.go('people');
  await ui.go('requests');
  assert.doesNotMatch(ui.page.innerHTML, /revocable-handle/);
  assert.match(ui.page.innerHTML, /None shared/);
});

test('community members refresh after another account joins', async () => {
  const responses = {'/api/communities/21/members': [member]};
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  await ui.go('community/21');
  assert.doesNotMatch(ui.page.innerHTML, /Newly Joined Student/);
  responses['/api/communities/21/members'] = [member, {...member, studentId: 44, fullName: 'Newly Joined Student'}];
  await ui.go('people');
  await ui.go('community/21');
  assert.match(ui.page.innerHTML, /Newly Joined Student/);
});

test('refreshing navigation preserves the current account introduction draft', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  await ui.go('profile');
  await ui.input('bio', 'An unsaved draft that must survive refresh.');
  await ui.go('people');
  await ui.go('profile');
  assert.equal(ui.element('profile-form').elements.bio.value, 'An unsaved draft that must survive refresh.');
  assert.match(ui.element('profile-save-status').textContent, /Unsaved/);
  assert.ok(!ui.requests.some(request => request.path === '/api/profile' && request.method === 'PUT'));
});

test('unchanged profile fields do not mask newer saved data after a refresh', async () => {
  const responses = {'/api/profile': {studentId: 41, fullName: 'Original Name', bio: '', interests: []}};
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  await ui.go('profile');
  responses['/api/profile'] = {studentId: 41, fullName: 'Updated Name', bio: 'Updated elsewhere', interests: []};
  await ui.go('people');
  await ui.go('profile');
  assert.equal(ui.element('profile-form').elements.fullName.value, 'Updated Name');
  assert.equal(ui.element('profile-form').elements.bio.value, 'Updated elsewhere');
  assert.equal(ui.element('profile-save-status').textContent, 'Saved.');
});

test('edits made while saving a profile remain unsaved after that request finishes', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  await ui.go('profile');
  const release = ui.hold('PUT', '/api/profile');
  const saving = ui.submit('profile-form', {bio: 'Submitted bio'});
  await ui.settle();
  await ui.input('bio', '');
  release(); await saving;
  assert.equal(ui.element('profile-form').elements.bio.value, '');
  assert.match(ui.element('profile-save-status').textContent, /Unsaved/);
});

test('an older navigation response cannot overwrite a newer page result', async () => {
  const responses = {'/api/connections?view=incoming': [], '/api/connections?view=outgoing': [], '/api/connections?view=accepted': []};
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  const release = ui.holdNext('GET', '/api/connections?view=incoming', [{...member, fullName: 'Stale Student', connectionId: 34, status: 'PENDING'}]);
  const oldNavigation = ui.go('requests');
  await ui.settle();
  assert.match(ui.page.innerHTML, /Loading/);
  responses['/api/connections?view=incoming'] = [{...member, fullName: 'Current Student', connectionId: 35, status: 'PENDING'}];
  await ui.go('people');
  await ui.go('requests');
  assert.match(ui.page.innerHTML, /Current Student/);
  release(); await oldNavigation;
  assert.match(ui.page.innerHTML, /Current Student/);
  assert.doesNotMatch(ui.page.innerHTML, /Stale Student/);
});

test('logout prevents a pending account load from restoring the old session', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  const release = ui.holdNext('GET', '/api/profile', {studentId: 41, fullName: 'Stale Private Student', bio: 'Do not restore this account', interests: []});
  const oldNavigation = ui.go('requests');
  await ui.settle();
  await ui.click('logout');
  release(); await oldNavigation;
  assert.match(ui.page.innerHTML, /id="auth-form"/);
  assert.doesNotMatch(ui.markup, /Stale Private Student|Do not restore this account/);
  const sessionReads = ui.requests.filter(request => request.path === '/api/session').length;
  await ui.go('profile');
  assert.equal(ui.requests.filter(request => request.path === '/api/session').length, sessionReads);
  assert.equal(ui.context.location.hash, '#login');
});

test('a stale failed request cannot log out a newer valid page load', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  const release = ui.holdNext('GET', '/api/session', {error: 'UNAUTHENTICATED'}, 401);
  const oldNavigation = ui.go('requests');
  await ui.settle();
  await ui.go('people');
  release(); await oldNavigation;
  assert.equal(ui.context.location.hash, '#people');
  assert.match(ui.page.innerHTML, /<h1>People<\/h1>/);
  assert.doesNotMatch(ui.markup, /id="auth-form"/);
});

test('an externally changed session never inherits another account profile draft', async () => {
  const firstProfile = {studentId: 41, fullName: 'First Account', bio: 'Saved first bio', interests: []};
  const responses = {'/api/session': {studentId: 41, hasProfile: true}, '/api/profile': firstProfile};
  const ui = await setup({signedIn: true, hasProfile: true, responses});
  await ui.go('profile');
  await ui.input('bio', 'Private unsaved text belonging only to the first account.');
  responses['/api/session'] = {studentId: 52, hasProfile: true};
  responses['/api/profile'] = {studentId: 52, fullName: 'Second Account', bio: 'Second account bio', interests: []};
  await ui.go('people');
  await ui.go('profile');
  assert.equal(ui.element('profile-form').elements.fullName.value, 'Second Account');
  assert.equal(ui.element('profile-form').elements.bio.value, 'Second account bio');
  assert.doesNotMatch(ui.markup, /Private unsaved text belonging/);
});

test('cancelling the block confirmation sends no database request', async () => {
  const ui = await setup({signedIn: true, hasProfile: true}); await ui.go('requests');
  ui.context.confirm = () => false;
  await ui.click('block', 31);
  assert.ok(!ui.requests.some(request => request.path === '/api/connections/31/block'));
});

test('student text is escaped before rendering HTML', async () => {
  const ui = await setup({signedIn: true, hasProfile: true, responses: {'/api/matches?limit=30': [{...member, fullName: '<img src=x onerror=alert(1)>', bio: '<script>bad()</script>'}]}});
  assert.match(ui.markup, /&lt;img/); assert.match(ui.markup, /&lt;script&gt;/);
  assert.doesNotMatch(ui.markup, /<img src=x|<script>bad/);
});

test('failed writes show an error and restore the control so the action can be retried', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  ui.fail('/api/connections', {method: 'POST', status: 500, error: 'DATABASE_ERROR'});
  const button = await ui.click('connect', 42);
  assert.equal(button.disabled, false); assert.ok(ui.error);
  await ui.click('connect', 42);
  assert.equal(ui.requests.filter(request => request.path === '/api/connections' && request.method === 'POST').length, 2);
});

test('overlapping clicks cannot create duplicate connection requests', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  const release = ui.hold('POST', '/api/connections');
  const first = ui.startClick('connect', 42); await ui.settle();
  const second = ui.startClick('connect', 42); await ui.settle();
  assert.equal(ui.requests.filter(request => request.path === '/api/connections' && request.method === 'POST').length, 1);
  release(); await Promise.all([first.promise, second.promise]); assert.equal(first.button.disabled, false);
});

test('expired sessions return to login after a protected action', async () => {
  const ui = await setup({signedIn: true, hasProfile: true});
  ui.fail('/api/connections', {method: 'POST', status: 401, error: 'UNAUTHENTICATED'});
  await ui.click('connect', 42); assert.equal(ui.context.location.hash, '#login'); assert.match(ui.markup, /id="auth-form"/);
});

test('the simple shell contains no animation, external assets or inline event handlers', () => {
  const index = fs.readFileSync('dist/index.html', 'utf8');
  const css = fs.readFileSync('dist/css/site.css', 'utf8');
  assert.doesNotMatch(index, /motion\.(?:js|css)|https?:\/\/|\son[a-z]+\s*=|\sstyle\s*=/i);
  assert.doesNotMatch(css, /@keyframes|\banimation\s*:|\btransition\s*:|scroll-behavior\s*:\s*smooth/i);
  assert.ok(!fs.existsSync('dist/js/motion.js'));
  assert.ok(!fs.existsSync('dist/css/motion.css'));
});
