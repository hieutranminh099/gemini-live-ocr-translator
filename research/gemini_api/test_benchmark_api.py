"""Offline contract checks, not live latency/quality benchmark results."""
import json
import unittest

import httpx

from benchmark_api import measure, payload_for, percentile, summaries, valid_translation


class BenchmarkTests(unittest.IsolatedAsyncioTestCase):
    async def sample(self, content, *, stream=False, endpoint='generateContent', status=200):
        captured = []
        def handle(request):
            captured.append(request)
            return httpx.Response(status, content=content, headers={'content-type': 'text/event-stream' if stream else 'application/json'})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            result = await measure(client, dict(id='a', source='你好。', reference='Xin chào.'),
                dict(model='gemini-3.5-flash-lite', level='minimal', stream=stream,
                     endpoint=endpoint, prompt='compact', store=False, max_tokens=256))
        self.assertNotIn('authorization', captured[0].headers)
        return result, json.loads(captured[0].content)

    async def test_nonstream_complete(self):
        result, payload = await self.sample(json.dumps({'candidates': [{'content': {'parts': [
            {'text': '{"translations":["Xin chào."]}'}]}, 'finishReason': 'STOP'}],
            'usageMetadata': {'promptTokenCount': 50}}))
        self.assertEqual('ok', result['status'])
        self.assertEqual('Xin chào.', result['translation'])
        self.assertIn('first_useful_complete_translation', result['timings_ms'])
        self.assertEqual('MINIMAL', payload['generationConfig']['thinkingConfig']['thinkingLevel'])
        self.assertEqual(256, payload['generationConfig']['maxOutputTokens'])

    async def test_stream_ignores_thought_and_requires_complete_json(self):
        events = [dict(candidates=[dict(content=dict(parts=[dict(text='private reasoning', thought=True)]))]),
            dict(candidates=[dict(content=dict(parts=[dict(text='{"translations":["Xin ')]))]),
            dict(candidates=[dict(content=dict(parts=[dict(text='chào."]}')]), finishReason='STOP')])]
        content = ''.join('data: ' + json.dumps(e) + '\n\n' for e in events)
        result, _ = await self.sample(content, stream=True)
        self.assertEqual('ok', result['status'])
        self.assertEqual('Xin chào.', result['translation'])
        self.assertLessEqual(result['timings_ms']['first_text'], result['timings_ms']['first_useful_complete_translation'])

    async def test_stream_incomplete_is_error_despite_valid_json(self):
        data = dict(candidates=[dict(content=dict(parts=[dict(text='{"translations":["Xin chào."]}')]), finishReason='MAX_TOKENS')])
        result, _ = await self.sample('data: ' + json.dumps(data) + '\n\n', stream=True)
        self.assertEqual('error', result['status'])
        self.assertNotIn('first_useful_complete_translation', result['timings_ms'])

    async def test_interactions_stream(self):
        events = [dict(event_type='step.delta', delta=dict(type='thought_summary', content={'text': 'ignore'})),
                  dict(event_type='step.delta', delta=dict(type='text', text='{"translations":["Xin chào."]}')),
                  dict(event_type='interaction.completed', interaction=dict(status='completed', usage=dict(total_input_tokens=15)))]
        result, payload = await self.sample(''.join('data: ' + json.dumps(e) + '\n\n' for e in events),
                                             stream=True, endpoint='interactions')
        self.assertEqual('ok', result['status'])
        self.assertFalse(payload['store'])
        self.assertEqual(15, result['usage']['total_input_tokens'])

    async def test_interactions_nonstream(self):
        body = dict(status='completed', steps=[dict(type='thought', summary=[dict(type='text', text='ignore')]),
            dict(type='model_output', content=[dict(type='text', text='{"translations":["Xin chào."]}')])])
        result, _ = await self.sample(json.dumps(body), endpoint='interactions')
        self.assertEqual('ok', result['status'])

    async def test_http_error_body_not_logged(self):
        result, _ = await self.sample('secret provider diagnostic', status=403)
        self.assertEqual('http_error', result['error'])
        self.assertNotIn('secret', json.dumps(result))
        self.assertIn('request_wall', result['timings_ms'])

    def test_summaries_do_not_hide_failures(self):
        rows = [dict(scenario={}, status='ok', timings_ms=dict(completion=10)),
                dict(scenario={}, status='error', timings_ms=dict(request_wall=500))]
        report = summaries(rows)[0]
        self.assertEqual((2, 1, 1), (report['attempts'], report['success'], report['errors']))
        self.assertEqual(10, report['success_only_quantiles_ms']['completion']['p95'])

    def test_complete_mapping_and_nearest_rank(self):
        for value in ('{"translations":[]}', '{"translations":["a","b"]}', '{}', '{"translations":[1]}', '{"translations":["  "]}', '{"translations":"a"}'):
            self.assertIsNone(valid_translation(value))
        self.assertEqual(95, percentile(list(range(1, 101)), .95))
        self.assertIsNone(percentile([], .95))

    def test_interactions_store_true_explicit(self):
        _, body = payload_for('你好', 'gemini-3.5-flash-lite', 'minimal', True,
                              'interactions', 'compact', True, 256)
        self.assertTrue(body['store'])
        self.assertNotIn('previous_interaction_id', body)


if __name__ == '__main__':
    unittest.main()
