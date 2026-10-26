"""Regression tests for locale text corruption, independent of game toolchains."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location('validate_resources', Path(__file__).resolve().parents[1] / 'validate_resources.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

class LocaleEncodingTest(unittest.TestCase):
    def errors(self, value):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'src/main/resources/assets/trakt/lang'
            folder.mkdir(parents=True)
            for language in validator.LANGUAGES:
                (folder / f'{language}.json').write_text(json.dumps({'text.trakt.test': value},ensure_ascii=False),encoding='utf-8')
            with patch.object(validator,'ROOT',root):
                return validator.validate()[1]

    def test_clean_accents_symbols_and_placeholders_are_accepted(self):
        self.assertEqual([],self.errors('ÄÖÜ äöü ß ¡áéíóúñ! · — × → ⚠ %1$s %2$s %%'))

    def test_utf8_misread_as_windows1252_is_rejected_in_each_locale(self):
        for value in ('Stage 1 Â· Harvest Wheat %s/%s','KupfermÃ¼nze','Fragmento mÃ¡gico','Path â€” home','Cargo %sÃ— %s','Шаг Ã— один'):
            with self.subTest(value=value):
                errors = self.errors(value)
                self.assertEqual(len(validator.LANGUAGES),len(errors),'Every affected locale must report its corrupt value')
                self.assertTrue(all('text.trakt.test' in error for error in errors))

    def test_replacement_characters_and_hidden_controls_are_rejected(self):
        for value in ('Broken \ufffd text','Hidden\u0081byte','Hidden\u200bspace'):
            with self.subTest(value=repr(value)):
                self.assertEqual(len(validator.LANGUAGES),len(self.errors(value)))

class LocaleContextTest(unittest.TestCase):
    def check(self, language, values):
        return validator.validate_catalog_context(language, values)

    def test_lens_lore_accepts_one_server_supplied_target_in_each_locale(self):
        key='item.trakt.cartographers_lens.lore.3'
        for locale in ('en_us','de_de','es_es'):
            self.assertEqual([],self.check(locale,{key:'Survey %s villages; claim the chapter reward.'}))
            self.assertTrue(self.check(locale,{key:'Survey %s of %s villages.'}))

    def test_german_reward_labels_reject_untranslated_wallet_and_ledger(self):
        for value in ('Wallet: %s','Ein Ledger für das Dorf','Farming-Ruf +5'):
            self.assertTrue(self.check('de_de',{'screen.trakt.questmaster.reward.currency':value}))
        self.assertEqual([],self.check('de_de',{'screen.trakt.questmaster.reward.currency':'Geldbeutel: %s'}))

    def test_spider_contract_keeps_the_fang_meaning(self):
        key='quest.trakt.pilgrim.contract.fangs_by_the_hedgerow.title'
        self.assertTrue(self.check('de_de',{key:'Fänge am Heckenrand'}))
        self.assertTrue(self.check('de_de',{key:'Hinterhalt am Heckenrand'}))
        self.assertEqual([],self.check('de_de',{key:'Fangzähne am Heckenrand'}))

    def test_lens_lore_requires_two_surveys_in_each_language(self):
        key='item.trakt.cartographers_lens.lore.3'
        for language,bad,good in (
            ('en_us','After three surveys it is installed.','After two village surveys it is installed.'),
            ('de_de','Nach drei Erfassungen wird sie eingesetzt.','Erfasse zwei Dörfer und hole die Kapitelbelohnung ab.'),
            ('es_es','Tras tres estudios se instala.','Tras estudiar dos pueblos se instala.'),
            ('ru_ru','После трёх обзоров устанавливается.','После двух обзоров деревень заберите награду главы.')
        ):
            with self.subTest(language=language):
                self.assertTrue(self.check(language,{key:bad}))
                self.assertEqual([],self.check(language,{key:good}))

    def test_ledger_gate_uses_the_actual_localized_market_story_title(self):
        title='quest.trakt.story.market_road_troubles.title'
        key='screen.trakt.guild_path.node.ledger.requirement'
        self.assertTrue(self.check('de_de',{title:'Unruhige Marktstraße',key:'Schließe Die leere Karawane ab.'}))
        self.assertEqual([],self.check('de_de',{title:'Unruhige Marktstraße',key:'Schließe „Unruhige Marktstraße“ ab.'}))

    def test_project_effect_label_matches_atlas_strip_label(self):
        key='quest.trakt.project.watch_bell.effect'
        self.assertTrue(self.check('de_de',{key:'Effekt: mehr Ruf.'}))
        self.assertEqual([],self.check('de_de',{key:'Wirkung: mehr Ruf.'}))

    def test_market_charter_explains_first_route_without_dropping_compass(self):
        key='quest.trakt.project.market_charter.effect'
        self.assertTrue(self.check('en_us',{key:'Effect: the pilgrim adds compass roadmarks.'}))
        self.assertEqual([],self.check('en_us',{key:'Effect: one provisional route; the pilgrim adds compass roadmarks.'}))

    def test_spanish_compass_aliases_do_not_become_another_npc(self):
        key='quest.trakt.special.surveyor_compass.offer.1'
        self.assertTrue(self.check('es_es',{key:'El Wayfinder te guía.'}))
        self.assertEqual([],self.check('es_es',{key:'La brújula del explorador te guía.'}))

    def test_russian_project_effect_label_matches_atlas_strip_label(self):
        key='quest.trakt.project.watch_bell.effect'
        self.assertTrue(self.check('ru_ru',{key:'Эффект: больше доверия.'}))
        self.assertEqual([],self.check('ru_ru',{key:'Действие: больше доверия.'}))

    def test_russian_market_charter_explains_first_route_without_dropping_compass(self):
        key='quest.trakt.project.market_charter.effect'
        self.assertTrue(self.check('ru_ru',{key:'Действие: паломник добавляет компасные метки.'}))
        self.assertEqual([],self.check('ru_ru',{key:'Действие: открывает один временный маршрут; паломник добавляет компасные метки.'}))

    def test_russian_untranslated_terms_are_rejected(self):
        self.assertTrue(self.check('ru_ru',{'screen.trakt.questmaster.reward.currency':'Кошелёк: Wallet'}))
        self.assertTrue(self.check('ru_ru',{'text.trakt.test':'Иди к Wayshrine.'}))
        self.assertEqual([],self.check('ru_ru',{'screen.trakt.questmaster.reward.currency':'Кошелёк: %s'}))

    def test_historical_counts_and_command_literals_are_allowed(self):
        values={'quest.trakt.story.restless_pens.chapter_2.offer.2':'Schere 29 Schafe und bringe 57 Wolle zum Questmeister.',
                'command.trakt.help':'/questmaster; /trakt admin wallet; Magic Shard'}
        self.assertEqual([],self.check('de_de',values))

if __name__ == '__main__': unittest.main()
