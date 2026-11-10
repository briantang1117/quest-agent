package com.quest.api.syllabus;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 调度入口仍由 Pipeline 检查持久化模式和迁移表，未迁移不扫描队列。 */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
class SyllabusScheduling {}
