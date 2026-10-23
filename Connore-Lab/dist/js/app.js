(() => {
  'use strict';

  const app = document.getElementById('app');
  const collegeDomain = window.CONNORE_CONFIG?.collegeDomain || 'sitpune.edu.in';
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
  const emptyState = () => ({
    session: null, profile: null, categories: [], interestIds: [], links: [],
    matches: [], communities: [], incoming: [], outgoing: [], accepted: [], members: {}
  });
  let state = emptyState();
  let profileDraft = null;
  let authEmail = '';
  let writing = false;
  let loading = true;
  let renderVersion = 0;
  let accountLoadVersion = 0;
  let navigationVersion = 0;
  let notice = '';
  let errorText = '';
  const searches = {people: '', communities: '', interests: ''};
  const route = () => location.hash.replace(/^#/, '') || 'people';
  const field = (form, name) => form.elements.namedItem ? form.elements.namedItem(name) : form.elements[name];
  const formValues = form => Object.fromEntries(new FormData(form));
  const disabled = () => state.profile ? '' : ' disabled';

  const messages = {
    NOT_COLLEGE_EMAIL: `Use your @${collegeDomain} college email.`,
    PASSWORD_TOO_SHORT: 'Choose a password with at least 8 characters.',
    EMAIL_EXISTS: 'An account with this email already exists. Log in instead.',
    BAD_CREDENTIALS: 'The email or password is incorrect.',
    INVALID_NAME: 'Enter your name using no more than 100 characters.',
    INVALID_DEPARTMENT: 'Keep the department within 60 characters.',
    INVALID_YEAR: 'Choose a year from 1 to 6, or leave it blank.',
    BIO_TOO_LONG: 'Keep your bio within 500 characters.',
    INVALID_HANDLE: 'Choose a platform and enter a handle of up to 100 characters.',
    REFUSED: 'This connection request is not available.',
    FORBIDDEN: 'This action is no longer available. Refresh the page and try again.',
    PROFILE_REQUIRED: 'Save your profile before continuing.',
    INTEREST_NOT_FOUND: 'This interest is no longer available. Refresh the page.',
    TOO_MANY_ATTEMPTS: 'Too many attempts. Wait 10 minutes before trying again.'
  };

  function showMessage(message = '', error = '') {
    notice = message;
    errorText = error;
    const status = document.getElementById('status');
    const alert = document.getElementById('error');
    if (status) status.textContent = notice;
    if (alert) alert.textContent = errorText;
  }

  function handleError(error) {
    if (error.status === 401 && error.code !== 'BAD_CREDENTIALS') {
      clearAccount();
      location.hash = 'login';
      render();
      showMessage('', 'Your session has ended. Log in to continue.');
      return;
    }
    showMessage('', messages[error.code || error.message] || 'Could not complete the request. Please try again.');
  }

  function clearAccount() {
    accountLoadVersion++;
    navigationVersion++;
    state = emptyState();
    profileDraft = null;
    searches.people = searches.communities = searches.interests = '';
  }

  function commitAccount(next, version) {
    if (version !== accountLoadVersion) return false;
    if (state.session?.studentId !== next.session.studentId) {
      profileDraft = null;
      searches.people = searches.communities = searches.interests = '';
    }
    state = next;
    return true;
  }

  async function loadAccount() {
    const version = ++accountLoadVersion;
    try {
      const session = await Api.call('GET', '/session');
      const categories = await Api.call('GET', '/interests');
      if (!session.hasProfile) return commitAccount({...emptyState(), session, categories}, version);
      const [profile, interestIds, links, matches, communities, incoming, outgoing, accepted] = await Promise.all([
        Api.call('GET', '/profile'), Api.call('GET', '/profile/interests'),
        Api.call('GET', '/profile/social-links'), Api.call('GET', '/matches?limit=30'),
        Api.call('GET', '/communities'), Api.call('GET', '/connections?view=incoming'),
        Api.call('GET', '/connections?view=outgoing'), Api.call('GET', '/connections?view=accepted')
      ]);
      return commitAccount({session, profile, categories, interestIds, links, matches, communities, incoming, outgoing, accepted, members: {}}, version);
    } catch (error) {
      if (version !== accountLoadVersion) return false;
      throw error;
    }
  }

  function captureDraft() {
    const form = document.getElementById('profile-form');
    if (form) {
      const values = formValues(form);
      profileDraft = writing || profileHasChanges(values) ? values : null;
    }
  }

  function profileHasChanges(values) {
    return ['fullName', 'department', 'yearOfStudy', 'bio'].some(key => String(values[key] || '') !== String(state.profile?.[key] || ''));
  }

  async function refreshAfterWrite(message) {
    try {
      if (!await loadAccount()) return;
      render();
      showMessage(message);
    } catch (error) {
      if (error.status === 401) handleError(error);
      else showMessage(message, 'Your change was saved, but the page could not reload its data. Refresh the page to check it.');
    }
  }

  async function performWrite(control, task) {
    if (writing || control?.disabled) return false;
    writing = true;
    if (control) control.disabled = true;
    showMessage('Saving…');
    try {
      await task();
      return true;
    } catch (error) {
      handleError(error);
      return false;
    } finally {
      writing = false;
      if (control) control.disabled = false;
    }
  }

  function formInput(id, label, value, attributes = '') {
    return `<div class="field"><label for="${id}">${label}</label><input id="${id}" name="${id}" value="${escapeHtml(value)}" ${attributes}></div>`;
  }

  function profilePage() {
    const profile = profileDraft || state.profile || {fullName: '', department: '', yearOfStudy: '', bio: ''};
    return `<h1>My profile</h1>
      ${!state.profile ? '<p>Save your name first, then choose interests and find people.</p>' : ''}
      <section aria-labelledby="profile-heading"><h2 id="profile-heading">About me</h2>
        <form id="profile-form">
          ${formInput('fullName', 'Full name', profile.fullName, 'required maxlength="100" autocomplete="name"')}
          <div class="form-grid">
            ${formInput('department', 'Department (optional)', profile.department, 'maxlength="60"')}
            <div class="field"><label for="yearOfStudy">Year (optional)</label><select id="yearOfStudy" name="yearOfStudy"><option value="">Not set</option>${[1, 2, 3, 4, 5, 6].map(year => `<option value="${year}"${Number(profile.yearOfStudy) === year ? ' selected' : ''}>${year}</option>`).join('')}</select></div>
          </div>
          <div class="field"><label for="bio">Bio (optional)</label><textarea id="bio" name="bio" maxlength="500" rows="3">${escapeHtml(profile.bio)}</textarea></div>
          <button type="submit">Save profile</button>
          <span id="profile-save-status" class="muted" role="status"></span>
        </form>
      </section>
      <section aria-labelledby="interests-heading"><h2 id="interests-heading">Interests</h2>
        <p class="muted">Selections save when you tick or untick them. Joining a community is separate.</p>
        <div class="field"><label for="interest-search">Find an interest</label><input id="interest-search" type="search" value="${escapeHtml(searches.interests)}"></div>
        <div id="interest-list" class="interest-list">${interestList()}</div>
      </section>
      <section aria-labelledby="privacy-heading"><h2 id="privacy-heading">Privacy</h2>
        <form id="privacy-form"><fieldset${disabled()}><legend>Choose what other students can see</legend>
          ${[['suggestible', 'Include me in people suggestions', state.profile?.suggestible ?? true], ['showDepartment', 'Show my department', state.profile?.showDepartment], ['showYear', 'Show my year', state.profile?.showYear]].map(([key, label, checked]) => `<label class="check"><input type="checkbox" id="${key}" name="${key}"${checked ? ' checked' : ''}> ${label}</label>`).join('')}
        </fieldset><button type="submit"${disabled()}>Save privacy</button></form>
      </section>
      <section aria-labelledby="handles-heading"><h2 id="handles-heading">Social handles</h2>
        <p class="muted">Only accepted connections can see handles you choose to share.</p>
        ${state.links.length ? `<ul>${state.links.map(link => `<li><strong>${escapeHtml(link.platform)}</strong>: ${escapeHtml(link.handle)} <label class="check"><input type="checkbox" data-link-id="${link.linkId}"${link.shared ? ' checked' : ''}> Share with accepted connections</label></li>`).join('')}</ul>` : '<p>No handles added.</p>'}
        <form id="social-form"><div class="form-grid">
          <div class="field"><label for="platform">Platform</label><select id="platform" name="platform"${disabled()}>${['Instagram', 'GitHub', 'LinkedIn', 'Discord', 'Other'].map(platform => `<option>${platform}</option>`).join('')}</select></div>
          ${formInput('handle', 'Handle or profile link', '', `required maxlength="100"${disabled()}`)}
        </div><button type="submit"${disabled()}>Add handle</button></form>
      </section>`;
  }

  function interestList() {
    const query = searches.interests.trim().toLowerCase();
    const groups = state.categories.map(category => ({...category, interests: category.interests.filter(interest => `${category.name} ${interest.name}`.toLowerCase().includes(query))})).filter(category => category.interests.length);
    if (!groups.length) return `<p>${query ? 'No interests match your search.' : 'The interest catalog is not available yet.'}</p>`;
    return groups.map(category => `<fieldset${disabled()}><legend>${escapeHtml(category.name)}</legend>${category.interests.map(interest => `<label class="check"><input type="checkbox" data-interest-id="${interest.interestId}"${state.interestIds.includes(interest.interestId) ? ' checked' : ''}> ${escapeHtml(interest.name)}</label>`).join('')}</fieldset>`).join('');
  }

  function connectionControls(person, contextInterest) {
    if (person.studentId === state.session.studentId) return '<span class="muted">You</span>';
    if (state.accepted.some(connection => connection.studentId === person.studentId)) return '<span class="muted">Connected</span>';
    if (state.outgoing.some(connection => connection.studentId === person.studentId && connection.status === 'PENDING')) return '<span class="muted">Request sent</span>';
    if (state.incoming.some(connection => connection.studentId === person.studentId)) return '<a href="#requests">Review incoming request</a>';
    return `<button type="button" data-action="connect" data-id="${person.studentId}" data-interest="${contextInterest || ''}">Send request</button>`;
  }

  function personDetails(person) {
    const metadata = [person.department, person.yearOfStudy ? `Year ${person.yearOfStudy}` : ''].filter(Boolean);
    const interests = person.sharedInterests || person.interests || [];
    return `<h3>${escapeHtml(person.fullName)}</h3>${metadata.length ? `<p class="muted">${escapeHtml(metadata.join(' · '))}</p>` : ''}${person.bio ? `<p>${escapeHtml(person.bio)}</p>` : ''}${interests.length ? `<p class="muted">${person.sharedInterests ? 'Shared interests' : 'Interests'}: ${interests.map(interest => escapeHtml(interest.name)).join(', ')}</p>` : ''}`;
  }

  function peopleList() {
    const query = searches.people.trim().toLowerCase();
    const matches = state.matches.filter(person => `${person.fullName} ${person.bio} ${(person.sharedInterests || []).map(interest => interest.name).join(' ')}`.toLowerCase().includes(query));
    if (!matches.length) return `<p>${query ? 'No people match your search.' : !state.interestIds.length ? 'Choose interests in your profile to find people.' : 'No new suggestions yet. You can also meet members in communities.'}</p>`;
    return matches.map(person => `<article class="person">${personDetails(person)}${connectionControls(person, person.sharedInterests?.[0]?.interestId)}</article>`).join('');
  }

  function peoplePage() {
    return `<h1>People</h1><p>Students with interests in common with you.</p>
      <div class="field"><label for="people-search">Search people</label><input type="search" id="people-search" value="${escapeHtml(searches.people)}"></div>
      <div id="people-list" class="list">${peopleList()}</div>`;
  }

  function communityControls(community) {
    return `<div class="actions"><button type="button" data-action="${community.joined ? 'leave' : 'join'}" data-id="${community.communityId}">${community.joined ? 'Leave community' : 'Join community'}</button><a href="#community/${community.communityId}">View members</a></div>`;
  }

  function communityList() {
    const query = searches.communities.trim().toLowerCase();
    const communities = state.communities.filter(community => `${community.name} ${community.category} ${community.description}`.toLowerCase().includes(query));
    if (!communities.length) return `<p>${query ? 'No communities match your search.' : 'No communities are available yet.'}</p>`;
    return communities.map(community => `<article><h2>${escapeHtml(community.name)}</h2><p class="muted">${escapeHtml(community.category)} · ${community.memberCount} ${community.memberCount === 1 ? 'member' : 'members'}${community.joined ? ' · Joined' : ''}</p><p>${escapeHtml(community.description)}</p>${communityControls(community)}</article>`).join('');
  }

  function communitiesPage() {
    return `<h1>Communities</h1><div class="field"><label for="community-search">Search communities</label><input type="search" id="community-search" value="${escapeHtml(searches.communities)}"></div><div id="community-list" class="list">${communityList()}</div>`;
  }

  async function membersPage(id, version) {
    const page = document.getElementById('page');
    const community = state.communities.find(item => item.communityId === id);
    if (!community) {
      page.innerHTML = '<h1>Community unavailable</h1><p><a href="#communities">Back to communities</a></p>';
      return;
    }
    page.innerHTML = '<p role="status">Loading members…</p>';
    try {
      const members = state.members[id] || await Api.call('GET', `/communities/${id}/members`);
      if (version !== renderVersion) return;
      state.members[id] = members;
      page.innerHTML = `<p><a href="#communities">Back to communities</a></p><h1>${escapeHtml(community.name)}</h1><p>${escapeHtml(community.description)}</p>${communityControls(community)}<section><h2>Members</h2><div class="list">${members.length ? members.map(person => `<article class="person">${personDetails(person)}${connectionControls(person, community.interestId)}</article>`).join('') : '<p>No members yet.</p>'}</div></section>`;
    } catch (error) {
      if (version !== renderVersion) return;
      page.innerHTML = '<h1>Members unavailable</h1><p><a href="#communities">Back to communities</a></p>';
      handleError(error);
    }
  }

  function requestSection(title, people, view) {
    return `<section><h2>${title}</h2>${people.length ? `<div class="list">${people.map(person => `<article class="person">${personDetails(person)}${view === 'accepted' ? `<p>Shared handles: ${(person.socialLinks || []).length ? person.socialLinks.map(link => `${escapeHtml(link.platform)}: ${escapeHtml(link.handle)}`).join('; ') : 'None shared.'}</p>` : ''}<div class="actions">${requestActions(person, view)}</div></article>`).join('')}</div>` : `<p>${view === 'accepted' ? 'No connections yet.' : 'No requests.'}</p>`}</section>`;
  }

  function requestActions(person, view) {
    if (view === 'outgoing' && person.status === 'CLOSED') {
      const interest = state.categories.some(category => category.interests.some(item => item.interestId === person.contextInterestId)) ? person.contextInterestId : null;
      return `<span class="muted">Request closed</span>${connectionControls(person, interest)}`;
    }
    return `${view === 'incoming' ? `<button type="button" data-action="accept" data-id="${person.connectionId}">Accept</button><button type="button" data-action="decline" data-id="${person.connectionId}">Decline</button>` : view === 'outgoing' ? '<span class="muted">Awaiting reply</span>' : ''}<button type="button" data-action="block" data-id="${person.connectionId}">Block student</button>`;
  }

  function requestsPage() {
    return `<h1>Requests and connections</h1><p class="muted">Requests expire after 48 hours. Only shared handles appear after acceptance.</p>${requestSection('Incoming requests', state.incoming, 'incoming')}${requestSection('Sent requests', state.outgoing, 'outgoing')}${requestSection('Connections', state.accepted, 'accepted')}`;
  }

  function authPage(register) {
    return `<h1>${register ? 'Create account' : 'Log in'}</h1><p>Meet students through shared interests.</p><form id="auth-form" data-register="${register}">
      ${formInput('email', 'College email', authEmail, `type="email" required maxlength="100" autocomplete="username" placeholder="you@${escapeHtml(collegeDomain)}"`)}
      <p class="muted">Use your @${escapeHtml(collegeDomain)} email.</p>
      <div class="field"><label for="password">Password${register ? ' (at least 8 characters)' : ''}</label><input id="password" name="password" type="password" required maxlength="200"${register ? ' minlength="8"' : ''} autocomplete="${register ? 'new-password' : 'current-password'}"></div>
      <button type="submit">${register ? 'Create account' : 'Log in'}</button></form>
      <p>${register ? 'Already registered? <a href="#login">Log in</a>' : 'New here? <a href="#register">Create an account</a>'}</p>`;
  }

  function render() {
    const version = ++renderVersion;
    let current = route();
    if (loading) {
      app.innerHTML = '<main class="content"><h1>Connore</h1><p role="status">Loading…</p></main>';
      return;
    }
    if (!state.session && current !== 'register') {
      current = 'login';
      if (route() !== current) location.hash = current;
    } else if (state.session && !state.profile) {
      current = 'profile';
      if (route() !== current) location.hash = current;
    } else if (state.session && !['people', 'communities', 'requests', 'profile'].includes(current) && !/^community\/\d+$/.test(current)) {
      current = 'people';
      if (route() !== current) location.hash = current;
    }
    const section = current.startsWith('community/') ? 'communities' : current;
    app.innerHTML = `<header class="site-header"><strong>Connore</strong>${state.session ? `<nav class="site-nav" aria-label="Main navigation">${[['people', 'People'], ['communities', 'Communities'], ['requests', 'Requests'], ['profile', 'My profile']].map(([path, label]) => `<a href="#${path}"${section === path ? ' aria-current="page"' : ''}>${label}</a>`).join('')}<button type="button" data-action="logout">Log out</button></nav>` : ''}</header><div id="status" class="content status" role="status">${escapeHtml(notice)}</div><div id="error" class="content error" role="alert">${escapeHtml(errorText)}</div><main id="page" class="${state.session ? 'content' : 'auth'}"></main>`;
    const page = document.getElementById('page');
    if (!state.session) page.innerHTML = authPage(current === 'register');
    else if (current.startsWith('community/')) void membersPage(Number(current.split('/')[1]), version);
    else page.innerHTML = current === 'profile' ? profilePage() : current === 'communities' ? communitiesPage() : current === 'requests' ? requestsPage() : peoplePage();
    if (current === 'profile') updateDraftStatus();
  }

  function updateDraftStatus() {
    const form = document.getElementById('profile-form');
    const status = document.getElementById('profile-save-status');
    if (!form || !status) return;
    const values = formValues(form);
    const changed = profileHasChanges(values);
    status.textContent = changed ? 'Unsaved profile changes.' : state.profile ? 'Saved.' : '';
  }

  document.addEventListener('input', event => {
    const target = event.target;
    const searchesById = {'people-search': ['people', 'people-list', peopleList], 'community-search': ['communities', 'community-list', communityList], 'interest-search': ['interests', 'interest-list', interestList]};
    const search = searchesById[target.id];
    if (search) {
      searches[search[0]] = target.value;
      document.getElementById(search[1]).innerHTML = search[2]();
    }
    if (['fullName', 'department', 'yearOfStudy', 'bio'].includes(target.id)) {
      captureDraft();
      updateDraftStatus();
    }
  });

  document.addEventListener('change', async event => {
    const target = event.target;
    if (target.id === 'yearOfStudy') {
      captureDraft();
      updateDraftStatus();
    }
    const interestId = Number(target.dataset.interestId);
    const linkId = Number(target.dataset.linkId);
    if (!interestId && !linkId) return;
    if (writing || target.disabled) {
      target.checked = !target.checked;
      showMessage('', 'Wait for the current change to finish saving.');
      return;
    }
    captureDraft();
    const checked = target.checked;
    const success = await performWrite(target, async () => {
      if (interestId) await Api.call(checked ? 'POST' : 'DELETE', checked ? '/profile/interests' : `/profile/interests/${interestId}`, checked ? {interestId} : undefined);
      else await Api.call('PUT', `/profile/social-links/${linkId}`, {shared: checked});
      await refreshAfterWrite(interestId ? 'Interests saved.' : checked ? 'Handle shared with accepted connections.' : 'Handle is private.');
    });
    if (!success) target.checked = !checked;
  });

  document.addEventListener('click', async event => {
    const button = event.target.closest('[data-action]');
    if (!button || button.disabled || writing) return;
    event.preventDefault();
    const action = button.dataset.action;
    const id = Number(button.dataset.id);
    if (action === 'block' && !window.confirm('Block this student? This ends the connection or request and prevents future requests. Blocking cannot be undone.')) return;
    captureDraft();
    await performWrite(button, async () => {
      if (action === 'logout') {
        await Api.call('POST', '/auth/logout', {});
        clearAccount();
        location.hash = 'login';
        render();
        showMessage('Logged out.');
      } else if (action === 'connect') {
        const result = await Api.call('POST', '/connections', {toStudentId: id, interestId: Number(button.dataset.interest) || null});
        await refreshAfterWrite(['CONNECTED', 'ALREADY_CONNECTED'].includes(result.result) ? 'Connection accepted. See Requests for shared handles.' : 'Request sent.');
      } else if (action === 'accept' || action === 'decline') {
        await Api.call('POST', `/connections/${id}/respond`, {accept: action === 'accept'});
        await refreshAfterWrite(action === 'accept' ? 'Connection accepted.' : 'Request declined.');
      } else if (action === 'block') {
        await Api.call('POST', `/connections/${id}/block`, {});
        await refreshAfterWrite('Student blocked.');
      } else if (action === 'join' || action === 'leave') {
        await Api.call(action === 'join' ? 'POST' : 'DELETE', `/communities/${id}/${action === 'join' ? 'join' : 'membership'}`, action === 'join' ? {} : undefined);
        await refreshAfterWrite(action === 'join' ? 'Community joined.' : 'Community left.');
      }
    });
  });

  document.addEventListener('submit', async event => {
    const form = event.target;
    if (!['auth-form', 'profile-form', 'privacy-form', 'social-form'].includes(form.id)) return;
    event.preventDefault();
    if (writing) return;
    const submit = form.querySelector('[type="submit"]');
    const values = formValues(form);
    await performWrite(submit, async () => {
      if (form.id === 'auth-form') {
        const register = form.dataset.register === 'true';
        authEmail = values.email;
        await Api.call('POST', `/auth/${register ? 'register' : 'login'}`, values);
        if (register) {
          location.hash = 'login';
          render();
          showMessage('Account created. Log in to set up your profile.');
        } else {
          if (!await loadAccount()) return;
          location.hash = state.profile ? 'people' : 'profile';
          render();
          showMessage('Logged in.');
        }
      } else if (form.id === 'profile-form') {
        const submittedDraft = {...values};
        captureDraft();
        values.yearOfStudy = Number(values.yearOfStudy) || null;
        state.profile = await Api.call('PUT', '/profile', values);
        state.session.hasProfile = true;
        const editedWhileSaving = profileDraft && ['fullName', 'department', 'yearOfStudy', 'bio'].some(key => String(profileDraft[key] || '') !== String(submittedDraft[key] || ''));
        if (!editedWhileSaving) profileDraft = null;
        await refreshAfterWrite('Profile saved.');
      } else if (form.id === 'privacy-form') {
        captureDraft();
        const flags = Object.fromEntries(['suggestible', 'showDepartment', 'showYear'].map(key => [key, field(form, key).checked]));
        await Api.call('PUT', '/profile/privacy', flags);
        await refreshAfterWrite('Privacy saved.');
      } else {
        captureDraft();
        await Api.call('POST', '/profile/social-links', values);
        await refreshAfterWrite('Handle added. It is private until you choose to share it.');
      }
    });
  });

  window.addEventListener('hashchange', async () => {
    const version = ++navigationVersion;
    captureDraft();
    if (!state.session || !state.profile || writing) {
      render();
      return;
    }
    // Fetch other students' changes when visiting a page. Keep unsaved profile text.
    renderVersion++;
    document.getElementById('page').innerHTML = '<p role="status">Loading…</p>';
    try {
      if (!await loadAccount() || version !== navigationVersion) return;
      render();
    } catch (error) {
      if (version !== navigationVersion) return;
      render();
      handleError(error);
    }
  });

  async function boot() {
    render();
    try {
      if (!await loadAccount()) return;
      loading = false;
      render();
    } catch (error) {
      loading = false;
      if (error.status === 401) {
        clearAccount();
        render();
      } else {
        app.innerHTML = '<main class="content"><h1>Connore</h1><p>Could not load Connore. Check that the server and database are running, then reload this page.</p></main>';
      }
    }
  }

  void boot();
})();
