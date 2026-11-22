import { createClient } from 'npm:@supabase/supabase-js@2';

const NVIDIA_BASE = 'https://integrate.api.nvidia.com/v1';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, accept',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
};

function jsonError(message: string, status = 500) {
  return new Response(JSON.stringify({ error: { message } }), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!supabaseUrl || !serviceRoleKey) {
    return jsonError('Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY in Edge Function env.', 500);
  }

  const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey);

  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, '');

  let parsedBody: Record<string, unknown> | null = null;
  if (req.method === 'POST') {
    try {
      parsedBody = await req.json();
    } catch {
      return jsonError('Invalid JSON request body.', 400);
    }
  }

  const isModels =
    req.method === 'GET' ||
    path.endsWith('/models') ||
    url.searchParams.get('action') === 'models' ||
    parsedBody?.action === 'models';

  const nowIso = new Date().toISOString();
  const { data: keys, error: keyError } = await supabaseAdmin
    .from('api_keys')
    .select('id, api_key, name')
    .eq('service', 'nvidia')
    .eq('is_active', true)
    .or(`cooldown_until.is.null,cooldown_until.lte.${nowIso}`)
    .order('last_used_at', { ascending: true, nullsFirst: true })
    .limit(3);

  if (keyError) {
    return jsonError(`Database error reading api_keys: ${keyError.message}`, 500);
  }
  if (!keys || keys.length === 0) {
    return jsonError('No active NVIDIA API keys available in api_keys (missing, inactive, or in cooldown).', 503);
  }

  let upstreamRes: Response | null = null;

  for (let i = 0; i < keys.length; i++) {
    const keyRow = keys[i];
    if (!keyRow.api_key) continue;

    // Record usage timestamp
    await supabaseAdmin
      .from('api_keys')
      .update({ last_used_at: new Date().toISOString() })
      .eq('id', keyRow.id);

    if (isModels) {
      upstreamRes = await fetch(`${NVIDIA_BASE}/models`, {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${keyRow.api_key}`,
          Accept: 'application/json',
        },
      });
    } else {
      const payload = { ...(parsedBody || {}) };
      delete payload.action;

      upstreamRes = await fetch(`${NVIDIA_BASE}/chat/completions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${keyRow.api_key}`,
          'Content-Type': 'application/json',
          Accept: payload.stream ? 'text/event-stream' : 'application/json',
        },
        body: JSON.stringify(payload),
      });
    }

    // If rate-limited, put this key on a 60s cooldown and try next key if available
    if (upstreamRes.status === 429) {
      const cooldownIso = new Date(Date.now() + 60_000).toISOString();
      await supabaseAdmin
        .from('api_keys')
        .update({ cooldown_until: cooldownIso })
        .eq('id', keyRow.id);

      if (i < keys.length - 1) {
        await upstreamRes.body?.cancel();
        continue;
      }
    }

    break;
  }

  if (!upstreamRes) {
    return jsonError('Failed to contact NVIDIA upstream API.', 502);
  }

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    headers: {
      ...corsHeaders,
      'Content-Type': upstreamRes.headers.get('content-type') || 'application/json',
      'Cache-Control': 'no-cache',
    },
  });
});