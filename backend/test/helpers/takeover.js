/**
 * Signs in the way a test needs: when another session holds the account, the pending login request is approved straight in the store (standing in for the
 * signed-in phone's "Yes, It's Me") and the request is then redeemed exactly as a real second device would. Returns the final response.
 */
export async function loginTakingOver(store, post, body, path = '/v1/auth/login') {
  const first = await post(path, body);
  if (first.status !== 409) return first;
  const info = await first.clone().json();
  if (!info.requestId) return first;
  const c = await store.getChallenge(info.requestId);
  await store.putChallenge(c.id, { ...c, data: { ...c.data, status: 'approved' } });
  return post(path, { ...body, approval: { requestId: info.requestId, pollToken: info.pollToken } });
}
