"""Persist the agent's inspection of all 100 CPU greedy outputs.

These are diagnostic annotations, not a blind human panel or an accuracy score.
References are not edited to favor the model.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
source = json.loads((HERE/'results/cpu-int8-t4-b1.json').read_text(encoding='utf-8'))
major = {
    6: '爱国竞赛 mistranslated as a race to seize a country.',
    9: '程序 becomes thủ tục although context is downloading software.',
    11: 'Who is in your household becomes whether anyone is in the family.',
    13: 'Drops 药 (medicine), the object of the question.',
    15: 'Idiom 临时抓瞎 becomes temporarily blind; advance warning omitted.',
    18: 'Adds discovering truth; omits the intended explanation to comrades.',
    19: 'Entire third-wheel sentence omitted.',
    26: 'Omits weighing the decision and repeats mình unnaturally.',
    28: 'Omits being worthy of the people/country and changes subject logic.',
    32: 'Question asking room number reduced to Phòng của tôi?',
    35: 'Drops second speaker response and address; only first question translated.',
    36: 'Names and idiom corrupted into nonsensical dragon-skin clothing.',
    38: 'Copying pages becomes obtaining pieces of paper.',
    43: 'Embarrassing the company becomes fighting the company.',
    45: 'Ordinal visitor asking about a matter becomes asking about people.',
    48: 'Does not understand Cantonese becomes joking about Guangzhou.',
    49: 'Friend of my friend becomes my friend.',
    53: 'Incomplete ungrammatical final clause; blindly following crowd omitted.',
    62: 'Drops whole day of rain and changes temporal relation.',
    66: 'Regret not meeting earlier becomes enemies of each other.',
    68: 'Having to follow her becomes no option except her.',
    75: 'Walked into classroom becomes must enter classroom.',
    79: 'Three exhortations collapsed into malformed sentence with changed subject.',
    82: 'Criticism becomes advice; source idiom also poorly reflected in reference.',
    84: 'Instant coffee mistranslated as a cup of mixed coffee.',
    85: 'Worth doing becomes able to do.',
    95: 'Strongly oppose project becomes do not want to argue with project.',
}
critical = {
    2: 'Negation reversed: you go so I need not go -> if you go I will go.',
    7: '有两下子 (skilled) becomes two people; invented literal numerical claim.',
    16: 'Rhetorical denial I never said that becomes asking what I said.',
    93: 'Rhetorical denial friend never bought anything becomes asking where bought.',
    100: '二百五十万 = 2,500,000; output says 2,000,000.',
}
minor = {
    1: 'Suggestion becomes future assertion.',
    4: 'Ambiguous subject in the not-knowing-what-thinking clause.',
    10: 'Literal do not be serious is less idiomatic than do not take it seriously.',
    12: 'Rhetorical force weakened.',
    24: 'When I return tomorrow omitted; reference is itself unaccented and incomplete.',
    25: 'Second person possession omitted.',
    34: 'Entered university becomes studied at university; duration preserved.',
    46: 'Students generalized to people.',
    51: 'Apology for manner of invitation becomes apology for bringing here.',
    55: 'Wherever you like reduced to ambiguous feel free/go ahead.',
    58: 'Japanese name Taro rendered as Anh Lang.',
    61: 'Alone omitted.',
    77: 'Machine generalized to it.',
    80: 'About to leave becomes will you go.',
    90: 'Aspect changes from going/walking to future.',
    91: 'Name Ramsbottom mistranslated literally.',
    94: 'There omitted.',
    96: 'Everyone omitted.',
    98: 'Sufficient permission becomes obligation.',
}
reference_issues = {
    14: 'Reference Trí thức is not the usual translation of 知识 (knowledge).',
    24: 'Reference lacks Vietnamese diacritics and the return clause.',
    33: 'Reference says came down from mountains; source says used to be in mountains.',
    47: '娶你的 literally concerns marrying; reference Ghét anh suggests corrupted source.',
    82: 'Reference does not clearly preserve original criticism and 一风吹 idiom.',
    89: 'Reference adds a few things absent from source let us try.',
}
annotations = []
for number, row in enumerate(source['rows'], 1):
    severity = 'critical' if number in critical else 'major' if number in major else 'minor' if number in minor else 'acceptable'
    note = (critical | major | minor).get(number, 'Core meaning preserved; pronoun variants depend on absent dialogue context.')
    annotations.append({**row, 'inspection_index': number, 'agent_severity': severity,
                        'agent_note': note, 'reference_issue': reference_issues.get(number)})
report = {'reviewer': 'Codex agent; not a human panel', 'blind': False,
          'all_100_sources_references_and_cpu_greedy_outputs_read': True,
          'naturalness_certified': False, 'accuracy_percentage': None,
          'critical_negation_or_number_errors': len(critical),
          'reference_issues_retained_and_not_rewritten': len(reference_issues),
          'scope': 'CPU int8 beam1; all thread variants produce identical output; beam4 differences inspected separately.',
          'decision': 'Do not deploy OPUS-MT initial translations in the main application.',
          'rows': annotations}
(HERE/'results/agent-review100.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print('Recorded 100 diagnostic annotations, including five critical negation/number errors; no human accuracy claim.')
