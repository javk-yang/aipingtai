import logging
import re

from app.skills.schemas import SkillDescriptor

logger = logging.getLogger(__name__)


def match_skill(prompt: str, skills: list[SkillDescriptor]) -> SkillDescriptor | None:
    """按触发规则匹配技能：keyword 命中任一 value 即中；regex 命中 pattern 即中。"""
    for skill in skills:
        for trigger in skill.triggers:
            if trigger.type == "keyword":
                for keyword in trigger.values:
                    if keyword and keyword in prompt:
                        return skill
            elif trigger.type == "regex" and trigger.pattern:
                try:
                    if re.search(trigger.pattern, prompt):
                        return skill
                except re.error as exc:
                    logger.warning("invalid skill regex skill=%s pattern=%s: %s",
                                   skill.code, trigger.pattern, exc)
    return None
