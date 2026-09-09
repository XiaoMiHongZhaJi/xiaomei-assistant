package com.xiaomei.assistant.xposed

import com.xiaomei.assistant.runtime.RuntimeContainer
import com.xiaomei.assistant.model.AivsAsrBlacklistMatcher
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.CustomCommandExecutor
import com.xiaomei.assistant.model.CustomCommandMatcher
import com.xiaomei.assistant.status.AivsDebugSnapshotStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class AivsRuntimeHook : HostHook {
  override val name: String = "AivsRuntimeHook"

  private val firstAudioLogged = AtomicBoolean(false)
  private val pendingReplacement = ThreadLocal<PendingReplacement?>()
  private val pendingLowLevelReplacement = ThreadLocal<PendingReplacement?>()
  private val delayedInstructionBatches = ConcurrentHashMap<String, DelayedInstructionBatch>()
  private val delayedLowLevelBatches = ConcurrentHashMap<String, DelayedInstructionBatch>()
  private val processRecognizeText = ConcurrentHashMap<String, String>()
  private val processTruncationSeen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

  override fun install(context: HookContext): Boolean {
    if (context.processName != TargetPackages.MI_HEALTH_DEVICE) {
      return false
    }
    val classLoader = context.classLoader ?: run {
      HookLog.w("Skip AIVS hook because classLoader is null")
      return false
    }

    var installed = false
    installed = hookVoiceCallback(classLoader) || installed
    installed = hookRecognizerManager(classLoader) || installed
    installed = hookAivsService(classLoader) || installed
    installed = hookInstructionCapability(classLoader) || installed
    installed = hookAivsServiceSend(classLoader) || installed
    return installed
  }

  private fun hookVoiceCallback(classLoader: ClassLoader): Boolean {
    val voiceConfigClass = runCatching {
      Class.forName(VOICE_CONFIG_CLASS, false, classLoader)
    }.getOrNull() ?: return false

    var hooked = false
    runCatching {
      XposedHelpers.findAndHookMethod(
        VOICE_CALLBACK_CLASS,
        classLoader,
        "onStartSession",
        voiceConfigClass,
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            firstAudioLogged.set(false)
            val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
            val codec = invokeAnyZeroArg(param.args.firstOrNull(), "getRecordCodec")?.toString() ?: "unknown"
            val state = did.takeIf { it != "unknown" }?.let { AivsSessionStore.beginSession(it) }
            if (did != "unknown") {
              clearProcessAsrState(did)
            }
            if (did != "unknown") {
              AivsDebugSnapshotStore.reportSessionStart(
                did = did,
                sessionId = state?.sessionId ?: -1,
                detail = "onStartSession codec=$codec"
              )
            }
            HookLog.i(
              "AIVS session start did=$did sessionId=${state?.sessionId ?: -1} codec=$codec"
            )
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook onStartSession failed", it)
    }

    runCatching {
      XposedHelpers.findAndHookMethod(
        VOICE_CALLBACK_CLASS,
        classLoader,
        "onAudioData",
        ByteArray::class.java,
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val data = param.args.firstOrNull() as? ByteArray ?: return
            if (firstAudioLogged.compareAndSet(false, true)) {
              val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
              HookLog.i("AIVS first audio chunk did=$did size=${data.size}")
            }
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook onAudioData failed", it)
    }

    runCatching {
      XposedHelpers.findAndHookMethod(
        VOICE_CALLBACK_CLASS,
        classLoader,
        "onStopSession",
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            firstAudioLogged.set(false)
            val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
            val state = did.takeIf { it != "unknown" }?.let(AivsSessionStore::endSession)
            if (did != "unknown") {
              AivsDebugSnapshotStore.reportEvent(
                did = did,
                sessionId = state?.sessionId ?: -1,
                event = "onStopSession"
              )
            }
            HookLog.i("AIVS session stop did=$did sessionId=${state?.sessionId ?: -1}")
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook onStopSession failed", it)
    }
    return hooked
  }

  private fun hookRecognizerManager(classLoader: ClassLoader): Boolean {
    var hooked = false
    runCatching {
      val targetClass = Class.forName(BASE_SPEECH_RECOGNIZER_MANAGER_CLASS, false, classLoader)
      val unhooks = XposedBridge.hookAllMethods(
        targetClass,
        "prepareAudioInput",
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val did = extractDidFromArgs(param.args) ?: invokeZeroArg(param.thisObject, "getCurrentSessionDid") ?: "unknown"
            val sessionId = extractSessionIdFromArgs(param.args)
            val state = did.takeIf { it != "unknown" }?.let { AivsSessionStore.beginSession(it, sessionId) }
            if (did != "unknown") {
              clearProcessAsrState(did)
            }
            if (did != "unknown") {
              AivsDebugSnapshotStore.reportSessionStart(
                did = did,
                sessionId = state?.sessionId ?: sessionId,
                detail = "prepareAudioInput"
              )
              AivsDebugSnapshotStore.reportHookHit(
                point = "prepareAudioInput",
                did = did,
                sessionId = state?.sessionId ?: sessionId,
                detail = methodSignature(param.method as? Method)
              )
            }
            HookLog.i(
              "AIVS prepareAudioInput did=$did sessionId=${state?.sessionId ?: sessionId} " +
                "method=${methodSignature(param.method as? Method)} args=${argTypes(param.args)}"
            )
          }
        }
      )
      AivsDebugSnapshotStore.reportHookInstalled("prepareAudioInput", "count=${unhooks.size}")
      HookLog.i("Hooked prepareAudioInput overloads count=${unhooks.size}")
      hooked = unhooks.isNotEmpty()
    }.onFailure {
      HookLog.w("Hook prepareAudioInput failed", it)
      AivsDebugSnapshotStore.reportHookError("prepareAudioInput", it.message ?: it.javaClass.simpleName)
    }

    runCatching {
      XposedHelpers.findAndHookMethod(
        BASE_SPEECH_RECOGNIZER_MANAGER_CLASS,
        classLoader,
        "startExpectSpeech",
        Class.forName(POST_BACK_CLASS, false, classLoader),
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val did = invokeZeroArg(param.thisObject, "getCurrentSessionDid") ?: "unknown"
            val sessionId = did.takeIf { it != "unknown" }
              ?.let { AivsSessionStore.get(it)?.sessionId } ?: -1
            if (did != "unknown") {
              AivsDebugSnapshotStore.reportEvent(
                did = did,
                sessionId = sessionId,
                event = "startExpectSpeech"
              )
            }
            HookLog.i("AIVS startExpectSpeech did=$did sessionId=$sessionId")
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook startExpectSpeech failed", it)
      AivsDebugSnapshotStore.reportHookError("startExpectSpeech", it.message ?: it.javaClass.simpleName)
    }

    runCatching {
      XposedHelpers.findAndHookMethod(
        BASE_SPEECH_RECOGNIZER_MANAGER_CLASS,
        classLoader,
        "dialogFinish",
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val did = invokeZeroArg(param.thisObject, "getCurrentSessionDid") ?: "unknown"
            val state = did.takeIf { it != "unknown" }?.let(AivsSessionStore::get)
            if (did != "unknown") {
              AivsDebugSnapshotStore.reportEvent(
                did = did,
                sessionId = state?.sessionId ?: -1,
                event = "dialogFinish"
              )
            }
            HookLog.i("AIVS dialogFinish did=$did sessionId=${state?.sessionId ?: -1}")
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook dialogFinish failed", it)
      AivsDebugSnapshotStore.reportHookError("dialogFinish", it.message ?: it.javaClass.simpleName)
    }
    return hooked
  }

  private fun hookAivsService(classLoader: ClassLoader): Boolean {
    var hooked = false
    runCatching {
      XposedHelpers.findAndHookMethod(
        BASE_AIVS_SERVICE_CLASS,
        classLoader,
        "doAsrFirstTrace",
        String::class.java,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            val text = param.args.firstOrNull() as? String ?: return
            if (text.isNotBlank()) {
              val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
              val sessionId = did.takeIf { it != "unknown" }
                ?.let { AivsSessionStore.get(it)?.sessionId } ?: -1
              HookLog.i("AIVS ASR partial did=$did sessionId=$sessionId text=${text.take(80)}")
            }
          }
        }
      )
      hooked = true
    }.onFailure {
      HookLog.w("Hook doAsrFirstTrace failed", it)
    }

    runCatching {
      val targetClass = Class.forName(BASE_AIVS_SERVICE_CLASS, false, classLoader)
      val unhooks = XposedBridge.hookAllMethods(
        targetClass,
        "doAsrFinalTrace",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            val text = extractAsrTextFromArgs(param.args) ?: return
            if (text.isNotBlank()) {
              val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
              if (did == "unknown") {
                HookLog.w("Skip AIVS final ASR because did is unknown")
                return
              }
              val state = AivsSessionStore.updateFinalAsr(did, text)
              clearProcessAsrState(did)
              AivsDebugSnapshotStore.reportFinalAsr(did, state.sessionId, text)
              AivsDebugSnapshotStore.reportHookHit(
                point = "doAsrFinalTrace",
                did = did,
                sessionId = state.sessionId,
                detail = methodSignature(param.method as? Method)
              )
              HookLog.i(
                "AIVS ASR final did=$did sessionId=${state.sessionId} " +
                  "method=${methodSignature(param.method as? Method)} text=${text.take(80)}"
              )
              triggerLlmIfNeeded(did)
            }
          }
        }
      )
      AivsDebugSnapshotStore.reportHookInstalled("doAsrFinalTrace", "count=${unhooks.size}")
      HookLog.i("Hooked doAsrFinalTrace overloads count=${unhooks.size}")
      hooked = unhooks.isNotEmpty()
    }.onFailure {
      HookLog.w("Hook doAsrFinalTrace failed", it)
      AivsDebugSnapshotStore.reportHookError("doAsrFinalTrace", it.message ?: it.javaClass.simpleName)
    }
    return hooked
  }

  private fun hookInstructionCapability(classLoader: ClassLoader): Boolean {
    val instructionClass = runCatching {
      Class.forName(INSTRUCTION_CLASS, false, classLoader)
    }.getOrNull() ?: return false

    var hooked = false
    val installedProcessMethods = mutableSetOf<String>()
    val installedSendMethods = mutableSetOf<String>()
    INSTRUCTION_CAPABILITY_CLASSES.forEach { className ->
      val targetClass = runCatching {
        Class.forName(className, false, classLoader)
      }.getOrNull()
      if (targetClass == null) {
        HookLog.w("Skip AIVS instruction capability hook because class is unavailable class=$className")
        AivsDebugSnapshotStore.reportHookError("instructionCapability", "class_unavailable:$className")
        return@forEach
      }

      runCatching {
        val methods = declaredMethodsInHierarchy(targetClass, "process") { method ->
          method.parameterTypes.size == 1 &&
            (
              method.parameterTypes[0].isAssignableFrom(instructionClass) ||
                instructionClass.isAssignableFrom(method.parameterTypes[0])
              )
        }
        if (methods.isEmpty()) {
          AivsDebugSnapshotStore.reportHookError("process", "method_unavailable:$className")
          HookLog.w("Hook instruction process missing class=$className")
          return@runCatching
        }
        var installedCount = 0
        methods.forEach { method ->
          if (installedProcessMethods.add(methodSignatureKey(method))) {
            XposedBridge.hookMethod(
              method,
              object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                  val summary = summarizeInstruction(param.args.firstOrNull())
                  val did = extractDidFromInstructionCapability(param.thisObject) ?: "unknown"
                  val sessionId = did.takeIf { it != "unknown" }?.let { AivsSessionStore.get(it)?.sessionId } ?: -1
                  AivsDebugSnapshotStore.reportHookHit(
                    point = "process",
                    did = did,
                    sessionId = sessionId,
                    detail = "${targetClass.simpleName} ${methodSignature(param.method as? Method)} $summary"
                  )
                  HookLog.i(
                    "AIVS process class=${targetClass.name} did=$did " +
                      "method=${methodSignature(param.method as? Method)} instruction=$summary"
                  )
                  val instruction = param.args.firstOrNull() ?: return
                  val hookedMethod = param.method as? Method ?: return
                  trackProcessAsr(did, instruction)
                  if (shouldDelayInstructionProcess(did, instruction, hookedMethod)) {
                    param.setResult(skipResultFor(hookedMethod))
                    delayInstructionProcess(
                      method = hookedMethod,
                      target = param.thisObject,
                      args = param.args.copyOf(),
                      did = did,
                      summary = summary
                    )
                  }
                }
              }
            )
            installedCount += 1
          }
        }
        AivsDebugSnapshotStore.reportHookInstalled("process", "$className count=$installedCount coverage=${methods.size}")
        HookLog.i("Hooked AIVS process class=$className count=$installedCount coverage=${methods.size}")
        hooked = true
      }.onFailure {
        HookLog.w("Hook instruction process failed class=${targetClass.name}", it)
        AivsDebugSnapshotStore.reportHookError("process", "${targetClass.name}:${it.message ?: it.javaClass.simpleName}")
      }

      runCatching {
        val methods = declaredMethodsInHierarchy(targetClass, "sendAivsInstructions") { method ->
          method.parameterTypes.isNotEmpty()
        }
        if (methods.isEmpty()) {
          AivsDebugSnapshotStore.reportHookError("sendAivsInstructions", "method_unavailable:$className")
          HookLog.w("Hook sendAivsInstructions missing class=$className")
          return@runCatching
        }
        var installedCount = 0
        methods.forEach { method ->
          if (installedSendMethods.add(methodSignatureKey(method))) {
            XposedBridge.hookMethod(
              method,
              object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                  pendingReplacement.remove()
                  val did = extractDidFromInstructionCapability(param.thisObject) ?: "unknown"
                  val methodSummary = "${targetClass.simpleName} ${methodSignature(param.method as? Method)} args=${argTypes(param.args)}"
                  if (did == "unknown") {
                    AivsDebugSnapshotStore.reportHookHit(
                      point = "sendAivsInstructions",
                      did = did,
                      sessionId = -1,
                      detail = "$methodSummary did_unavailable"
                    )
                    HookLog.w("Keep official reply because did is unavailable $methodSummary")
                    return
                  }
                  val instructions = extractInstructionList(param.args) ?: run {
                    AivsDebugSnapshotStore.reportHookHit(
                      point = "sendAivsInstructions",
                      did = did,
                      sessionId = AivsSessionStore.get(did)?.sessionId ?: -1,
                      detail = "$methodSummary instruction_list_unavailable"
                    )
                    HookLog.i(
                      "AIVS send to wearable class=${targetClass.name} did=$did " +
                        "method=${methodSignature(param.method as? Method)} args=${argTypes(param.args)}"
                    )
                    return
                  }
                  val instructionSummary = summarizeInstructionList(instructions)
                  val currentSessionId = did.takeIf { it != "unknown" }?.let { AivsSessionStore.get(it)?.sessionId } ?: -1
                  AivsDebugSnapshotStore.reportHookHit(
                    point = "sendAivsInstructions",
                    did = did,
                    sessionId = currentSessionId,
                    detail = "$methodSummary $instructionSummary"
                  )
                  HookLog.i(
                    "AIVS send to wearable class=${targetClass.name} did=$did " +
                      "method=${methodSignature(param.method as? Method)} args=${argTypes(param.args)} $instructionSummary"
                  )
                  val hookedMethod = param.method as? Method
                  if (hookedMethod != null && canSkipOriginal(hookedMethod) && shouldDelayTextReply(did, instructions)) {
                    param.setResult(skipResultFor(hookedMethod))
                    replaySendWhenReady(
                      method = hookedMethod,
                      target = param.thisObject,
                      args = param.args.copyOf(),
                      did = did,
                      instructionSummary = instructionSummary
                    )
                    return
                  }
                  val decision = evaluateReplacement(did, instructions)
                  if (!decision.shouldReplace) {
                    if (decision.logFallback) {
                      AivsDebugSnapshotStore.reportOfficialReply(
                        did = did,
                        sessionId = decision.state?.sessionId ?: -1,
                        reason = decision.reason,
                        instructionSummary = instructionSummary
                      )
                      HookLog.i("AIVS keep official reply did=$did reason=${decision.reason}")
                    }
                    return
                  }
                  val answer = decision.answer ?: return
                  val replacedCount = rewriteTextPayloads(instructions, answer)
                  if (replacedCount <= 0) {
                    AivsDebugSnapshotStore.reportOfficialReply(
                      did = did,
                      sessionId = decision.state?.sessionId ?: -1,
                      reason = "no_mutated_text_payload",
                      instructionSummary = instructionSummary
                    )
                    HookLog.i("AIVS keep official reply did=$did reason=no_mutated_text_payload")
                    return
                  }
                  pendingReplacement.set(PendingReplacement(did, replacedCount))
                  AivsDebugSnapshotStore.reportReplacementPrepared(
                    did = did,
                    sessionId = decision.state?.sessionId ?: -1,
                    instructionSummary = instructionSummary,
                    answerPreview = answer
                  )
                  HookLog.i(
                    "AIVS reply replacement prepared did=$did sessionId=${decision.state?.sessionId ?: -1} " +
                      "replaced=$replacedCount answer=${answer.take(80)}"
                  )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                  val pending = pendingReplacement.get() ?: return
                  pendingReplacement.remove()
                  if (param.throwable != null) {
                    AivsDebugSnapshotStore.reportOfficialReply(
                      did = pending.did,
                      sessionId = AivsSessionStore.get(pending.did)?.sessionId ?: -1,
                      reason = "host_method_threw",
                      instructionSummary = "sendAivsInstructions throwable=${param.throwable?.javaClass?.simpleName}"
                    )
                    HookLog.w(
                      "AIVS reply replacement aborted did=${pending.did} because host method threw",
                      param.throwable
                    )
                    return
                  }
                  val state = AivsSessionStore.markReplacementConsumed(pending.did) ?: return
                  AivsDebugSnapshotStore.reportReplacementCommitted(pending.did, state.sessionId)
                  HookLog.i(
                    "AIVS reply replacement committed did=${pending.did} sessionId=${state.sessionId} " +
                      "replaced=${pending.replacedCount}"
                  )
                  persistHistory(state)
                }
              }
            )
            installedCount += 1
          }
        }
        AivsDebugSnapshotStore.reportHookInstalled("sendAivsInstructions", "$className count=$installedCount coverage=${methods.size}")
        HookLog.i("Hooked AIVS sendAivsInstructions class=$className count=$installedCount coverage=${methods.size}")
        hooked = true
      }.onFailure {
        HookLog.w("Hook sendAivsInstructions failed class=${targetClass.name}", it)
        AivsDebugSnapshotStore.reportHookError("sendAivsInstructions", "${targetClass.name}:${it.message ?: it.javaClass.simpleName}")
      }
    }
    return hooked
  }

  private fun declaredMethodsInHierarchy(
    startClass: Class<*>,
    methodName: String,
    predicate: (Method) -> Boolean
  ): List<Method> {
    val methods = linkedMapOf<String, Method>()
    var current: Class<*>? = startClass
    while (current != null && current != Any::class.java) {
      current.declaredMethods
        .asSequence()
        .filter { it.name == methodName && predicate(it) }
        .forEach { method ->
          method.isAccessible = true
          methods.putIfAbsent(methodSignatureKey(method), method)
        }
      current = current.superclass
    }
    return methods.values.toList()
  }

  private fun methodSignatureKey(method: Method): String {
    return method.declaringClass.name + "#" + method.name +
      method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
  }

  private fun hookAivsServiceSend(classLoader: ClassLoader): Boolean {
    var hooked = false
    val installedMethods = mutableSetOf<String>()
    AIVS_SERVICE_CLASSES.forEach { className ->
      val targetClass = runCatching {
        Class.forName(className, false, classLoader)
      }.getOrNull()
      if (targetClass == null) {
        AivsDebugSnapshotStore.reportHookError("sendAivsInstruction", "class_unavailable:$className")
        HookLog.w("Skip low-level AIVS send hook because class is unavailable class=$className")
        return@forEach
      }
      runCatching {
        val methods = declaredMethodsInHierarchy(targetClass, "sendAivsInstruction") { method ->
          method.parameterTypes.size == 1
        }
        if (methods.isEmpty()) {
          AivsDebugSnapshotStore.reportHookError("sendAivsInstruction", "method_unavailable:$className")
          HookLog.w("Hook low-level sendAivsInstruction missing class=$className")
          return@runCatching
        }
        var installedCount = 0
        methods.forEach { method ->
          if (installedMethods.add(methodSignatureKey(method))) {
            XposedBridge.hookMethod(
              method,
              object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                  pendingLowLevelReplacement.remove()
                  val did = invokeZeroArg(param.thisObject, "getDid") ?: "unknown"
                  val sessionId = did.takeIf { it != "unknown" }?.let { AivsSessionStore.get(it)?.sessionId } ?: -1
                  val summary = summarizeWearableInstruction(param.args.firstOrNull())
                  AivsDebugSnapshotStore.reportHookHit(
                    point = "sendAivsInstruction",
                    did = did,
                    sessionId = sessionId,
                    detail = "${targetClass.simpleName} ${methodSignature(param.method as? Method)} $summary"
                  )
                  HookLog.i(
                    "AIVS low-level send class=${targetClass.name} did=$did " +
                      "method=${methodSignature(param.method as? Method)} $summary"
                  )
                  val instruction = param.args.firstOrNull() ?: return
                  val hookedMethod = param.method as? Method ?: return
                  if (delayedInstructionBatches.containsKey(did)) {
                    HookLog.i("AIVS low-level send observed while process delay owns replacement did=$did $summary")
                    return
                  }
                  if (canSkipOriginal(hookedMethod) && shouldDelayLowLevelReplyFrame(did, instruction)) {
                    param.setResult(skipResultFor(hookedMethod))
                    replayLowLevelSendWhenReady(
                      method = hookedMethod,
                      target = param.thisObject,
                      args = param.args.copyOf(),
                      did = did,
                      instructionSummary = summary
                    )
                    return
                  }
                  val decision = evaluateLowLevelReplacement(did, instruction)
                  if (!decision.shouldReplace) {
                    if (decision.logFallback) {
                      AivsDebugSnapshotStore.reportOfficialReply(
                        did = did,
                        sessionId = decision.state?.sessionId ?: -1,
                        reason = decision.reason,
                        instructionSummary = summary
                      )
                      HookLog.i("AIVS keep low-level official reply did=$did reason=${decision.reason}")
                    }
                    return
                  }
                  val answer = decision.answer ?: return
                  val replacedCount = rewriteLowLevelTextPayload(instruction, answer)
                  if (replacedCount <= 0) {
                    AivsDebugSnapshotStore.reportOfficialReply(
                      did = did,
                      sessionId = decision.state?.sessionId ?: -1,
                      reason = "no_mutated_text_payload",
                      instructionSummary = summary
                    )
                    HookLog.i("AIVS keep low-level official reply did=$did reason=no_mutated_text_payload")
                    return
                  }
                  pendingLowLevelReplacement.set(PendingReplacement(did, replacedCount))
                  AivsDebugSnapshotStore.reportReplacementPrepared(
                    did = did,
                    sessionId = decision.state?.sessionId ?: -1,
                    instructionSummary = summary,
                    answerPreview = answer
                  )
                  HookLog.i(
                    "AIVS low-level reply replacement prepared did=$did " +
                      "sessionId=${decision.state?.sessionId ?: -1} replaced=$replacedCount"
                  )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                  val pending = pendingLowLevelReplacement.get() ?: return
                  pendingLowLevelReplacement.remove()
                  if (param.throwable != null) {
                    AivsDebugSnapshotStore.reportOfficialReply(
                      did = pending.did,
                      sessionId = AivsSessionStore.get(pending.did)?.sessionId ?: -1,
                      reason = "host_low_level_method_threw",
                      instructionSummary = "sendAivsInstruction throwable=${param.throwable?.javaClass?.simpleName}"
                    )
                    HookLog.w(
                      "AIVS low-level reply replacement aborted did=${pending.did} because host method threw",
                      param.throwable
                    )
                    return
                  }
                  val state = AivsSessionStore.markReplacementConsumed(pending.did) ?: return
                  AivsDebugSnapshotStore.reportReplacementCommitted(pending.did, state.sessionId)
                  HookLog.i(
                    "AIVS low-level reply replacement committed did=${pending.did} " +
                      "sessionId=${state.sessionId} replaced=${pending.replacedCount}"
                  )
                  persistHistory(state)
                }
              }
            )
            installedCount += 1
          }
        }
        AivsDebugSnapshotStore.reportHookInstalled("sendAivsInstruction", "$className count=$installedCount coverage=${methods.size}")
        HookLog.i("Hooked low-level AIVS send class=$className count=$installedCount coverage=${methods.size}")
        hooked = true
      }.onFailure {
        HookLog.w("Hook low-level sendAivsInstruction failed class=${targetClass.name}", it)
        AivsDebugSnapshotStore.reportHookError("sendAivsInstruction", "${targetClass.name}:${it.message ?: it.javaClass.simpleName}")
      }
    }
    return hooked
  }

  private fun summarizeWearableInstruction(raw: Any?): String {
    if (raw == null) {
      return "wearable=null"
    }
    val code = readFieldValue(raw, "c")?.toString().orEmpty()
    val text = findNestedString(raw)?.take(80).orEmpty()
    return buildString {
      append(raw.javaClass.simpleName)
      append("{")
      if (code.isNotBlank()) {
        append("code=").append(code)
      }
      if (text.isNotBlank()) {
        if (code.isNotBlank()) {
          append(" ")
        }
        append("text=").append(text)
      }
      append("}")
    }
  }

  private fun readFieldValue(target: Any, fieldName: String): Any? {
    var current: Class<*>? = target.javaClass
    while (current != null && current != Any::class.java) {
      val field = current.declaredFields.firstOrNull { it.name == fieldName }
      if (field != null) {
        return runCatching {
          field.isAccessible = true
          field.get(target)
        }.getOrNull()
      }
      current = current.superclass
    }
    return null
  }

  private fun writeStringField(target: Any, fieldName: String, value: String): Boolean {
    var current: Class<*>? = target.javaClass
    while (current != null && current != Any::class.java) {
      val field = current.declaredFields.firstOrNull { it.name == fieldName }
      if (field != null) {
        return runCatching {
          field.isAccessible = true
          field.set(target, value)
          true
        }.getOrDefault(false)
      }
      current = current.superclass
    }
    return false
  }

  private fun findNestedString(target: Any?, depth: Int = 0, seen: MutableSet<Int> = mutableSetOf()): String? {
    if (target == null || depth > 2) {
      return null
    }
    if (target is CharSequence) {
      return target.toString().takeIf { it.isNotBlank() }
    }
    val className = target.javaClass.name
    if (className.startsWith("java.") || className.startsWith("kotlin.")) {
      return null
    }
    if (!seen.add(System.identityHashCode(target))) {
      return null
    }
    var current: Class<*>? = target.javaClass
    while (current != null && current != Any::class.java) {
      current.declaredFields.forEach { field ->
        val value = runCatching {
          field.isAccessible = true
          field.get(target)
        }.getOrNull() ?: return@forEach
        if (value is CharSequence && value.isNotBlank()) {
          return value.toString()
        }
        findNestedString(value, depth + 1, seen)?.let { return it }
      }
      current = current.superclass
    }
    return null
  }

  private fun shouldDelayInstructionProcess(did: String, instruction: Any, method: Method): Boolean {
    if (did == "unknown" || !canSkipOriginal(method)) {
      return false
    }
    val state = AivsSessionStore.get(did) ?: return false
    if (state.finalAsr.isNullOrBlank() || state.asrBlacklisted || state.replacementConsumed || state.llmFinished) {
      return false
    }
    val fullName = invokeZeroArg(instruction, "getFullName").orEmpty()
    val namespace = invokeZeroArg(instruction, "getNamespace").orEmpty()
    if (BLOCKED_FULL_NAMES.contains(fullName) || BLOCKED_NAMESPACES.contains(namespace)) {
      return false
    }
    return DELAYABLE_ANSWER_FULL_NAMES.contains(fullName)
  }

  private fun trackProcessAsr(did: String, instruction: Any) {
    if (did == "unknown") {
      return
    }
    val fullName = invokeZeroArg(instruction, "getFullName").orEmpty()
    when (fullName) {
      "SpeechRecognizer.RecognizeResult" -> {
        val text = extractPayloadText(invokeAnyZeroArg(instruction, "getPayload"))?.trim().orEmpty()
        if (text.isBlank()) {
          return
        }
        processRecognizeText[did] = text
        HookLog.i("AIVS process ASR candidate did=$did text=${text.take(80)}")
        if (processTruncationSeen.remove(did)) {
          promoteProcessAsrIfNeeded(did, text, "process_truncation_result")
        }
      }
      "System.TruncationNotification" -> {
        processTruncationSeen.add(did)
      }
      in DELAYABLE_ANSWER_FULL_NAMES -> {
        val text = processRecognizeText[did].orEmpty()
        if (text.isNotBlank()) {
          promoteProcessAsrIfNeeded(did, text, "process_reply_start")
        }
      }
    }
  }

  private fun promoteProcessAsrIfNeeded(did: String, text: String, source: String) {
    val current = AivsSessionStore.get(did) ?: AivsSessionStore.beginSession(did)
    if (!current.finalAsr.isNullOrBlank() || current.llmStarted) {
      return
    }
    val updated = AivsSessionStore.updateFinalAsr(did, text)
    clearProcessAsrState(did)
    AivsDebugSnapshotStore.reportFinalAsr(did, updated.sessionId, text)
    HookLog.i(
      "AIVS ASR final fallback did=$did sessionId=${updated.sessionId} " +
        "source=$source text=${text.take(80)}"
    )
    triggerLlmIfNeeded(did)
  }

  private fun clearProcessAsrState(did: String) {
    processRecognizeText.remove(did)
    processTruncationSeen.remove(did)
  }

  private fun canSkipOriginal(method: Method): Boolean {
    return method.returnType == Void.TYPE ||
      method.returnType == Boolean::class.javaPrimitiveType ||
      method.returnType == java.lang.Boolean::class.java
  }

  private fun skipResultFor(method: Method): Any? {
    return when (method.returnType) {
      Void.TYPE -> null
      Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> true
      else -> null
    }
  }

  private fun delayInstructionProcess(
    method: Method,
    target: Any?,
    args: Array<Any?>,
    did: String,
    summary: String
  ) {
    val state = AivsSessionStore.get(did) ?: run {
      replayOriginal(method, target, args, did, "delay_process_session_missing")
      return
    }
    val batch = delayedInstructionBatches.computeIfAbsent(did) {
      DelayedInstructionBatch(
        did = did,
        sessionId = state.sessionId,
        createdAt = System.currentTimeMillis()
      )
    }
    batch.items.add(DelayedInstruction(method, target, args, summary))
    HookLog.i(
      "AIVS process delayed did=$did sessionId=${state.sessionId} " +
        "size=${batch.items.size} instruction=$summary"
    )
    if (batch.started.compareAndSet(false, true)) {
      val container = HookRuntimeResolver.resolve()
      if (container == null) {
        flushDelayedInstructionBatch(did, "runtime_unavailable_for_process_delay", replace = false)
        return
      }
      container.appScope.launch {
        val deadline = System.currentTimeMillis() + REPLY_DELAY_TIMEOUT_MS
        var current = AivsSessionStore.get(did)
        while (current != null && !current.llmFinished && System.currentTimeMillis() < deadline) {
          kotlinx.coroutines.delay(REPLY_DELAY_POLL_MS)
          current = AivsSessionStore.get(did)
        }
        kotlinx.coroutines.delay(REPLY_BATCH_GRACE_MS)
        val finalState = AivsSessionStore.get(did)
        val canReplace = finalState != null &&
          finalState.llmFinished &&
          !finalState.llmAnswer.isNullOrBlank() &&
          !finalState.replacementConsumed
        val reason = when {
          finalState == null -> "session_missing_after_process_delay"
          !finalState.llmFinished -> "llm_timeout_before_process_replay"
          finalState.llmAnswer.isNullOrBlank() -> "llm_failed_or_empty_before_process_replay"
          finalState.replacementConsumed -> "already_replaced_before_process_replay"
          else -> "process_replacement"
        }
        flushDelayedInstructionBatch(did, reason, replace = canReplace)
      }
    }
  }

  private fun flushDelayedInstructionBatch(did: String, reason: String, replace: Boolean) {
    val batch = delayedInstructionBatches.remove(did) ?: return
    val state = AivsSessionStore.get(did)
    var replacedCount = 0
    if (replace && state?.llmAnswer?.isNotBlank() == true) {
      batch.items.forEach { item ->
        val instruction = item.args.firstOrNull()
        if (instruction != null && REPLACEABLE_FULL_NAMES.contains(invokeZeroArg(instruction, "getFullName"))) {
          replacedCount += rewriteTextPayloads(listOf(instruction), state.llmAnswer)
        }
      }
    }
    val instructionSummary = batch.items.joinToString(" | ") { it.summary }.take(240)
    if (replacedCount > 0 && state != null) {
      AivsDebugSnapshotStore.reportReplacementPrepared(
        did = did,
        sessionId = state.sessionId,
        instructionSummary = instructionSummary,
        answerPreview = state.llmAnswer.orEmpty()
      )
      HookLog.i(
        "AIVS reply replacement prepared did=$did sessionId=${state.sessionId} " +
          "replaced=$replacedCount answer=${state.llmAnswer.orEmpty().take(80)}"
      )
    } else if (state != null) {
      AivsDebugSnapshotStore.reportOfficialReply(
        did = did,
        sessionId = state.sessionId,
        reason = reason,
        instructionSummary = instructionSummary
      )
    }
    batch.items.forEach { item ->
      replayOriginal(item.method, item.target, item.args, did, reason)
    }
    if (replacedCount > 0 && state != null) {
      val consumed = AivsSessionStore.markReplacementConsumed(did) ?: state
      AivsDebugSnapshotStore.reportReplacementCommitted(did, consumed.sessionId)
      HookLog.i(
        "AIVS reply replacement committed did=$did sessionId=${consumed.sessionId} " +
          "replaced=$replacedCount delayed=${batch.items.size}"
      )
      persistHistory(consumed)
    }
  }

  private fun shouldDelayTextReply(did: String, instructions: List<*>): Boolean {
    val state = AivsSessionStore.get(did) ?: return false
    if (state.finalAsr.isNullOrBlank() || state.asrBlacklisted || state.replacementConsumed || state.llmFinished) {
      return false
    }
    return containsTextReply(instructions) && !containsBlockedInstruction(instructions)
  }

  private fun shouldDelayLowLevelTextReply(did: String, instruction: Any): Boolean {
    val state = AivsSessionStore.get(did) ?: return false
    if (state.finalAsr.isNullOrBlank() || state.asrBlacklisted || state.replacementConsumed || state.llmFinished) {
      return false
    }
    return isLowLevelReplaceableTextInstruction(instruction)
  }

  private fun shouldDelayLowLevelReplyFrame(did: String, instruction: Any): Boolean {
    val state = AivsSessionStore.get(did) ?: return false
    if (state.finalAsr.isNullOrBlank() || state.asrBlacklisted || state.replacementConsumed || state.llmFinished) {
      return false
    }
    return lowLevelInstructionCode(instruction) in LOW_LEVEL_DELAYABLE_CODES
  }

  private fun extractInstructionList(args: Array<Any?>): List<*>? {
    args.forEach { arg ->
      when (arg) {
        is List<*> -> return arg
        is Array<*> -> return arg.toList()
      }
    }
    return null
  }

  private fun extractDidFromArgs(args: Array<Any?>): String? {
    return args.firstOrNull { it is String && it.isNotBlank() } as? String
  }

  private fun extractSessionIdFromArgs(args: Array<Any?>): Int {
    args.forEach { arg ->
      when (arg) {
        is Byte -> return arg.toInt().and(0xFF)
        is Short -> return arg.toInt()
        is Int -> return arg
        is Long -> return arg.toInt()
      }
    }
    return -1
  }

  private fun extractAsrTextFromArgs(args: Array<Any?>): String? {
    args.filterIsInstance<String>()
      .lastOrNull { it.isNotBlank() }
      ?.let { return it }
    args.forEach { arg ->
      extractPayloadText(arg)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  private fun replaySendWhenReady(
    method: Method,
    target: Any?,
    args: Array<Any?>,
    did: String,
    instructionSummary: String
  ) {
    val state = AivsSessionStore.get(did)
    val container = HookRuntimeResolver.resolve()
    if (container == null || state == null) {
      HookLog.w("AIVS delay aborted did=$did reason=runtime_or_session_unavailable")
      replayOriginal(method, target, args, did, "runtime_or_session_unavailable")
      return
    }
    HookLog.i("AIVS send delayed did=$did sessionId=${state.sessionId} $instructionSummary")
    container.appScope.launch {
      val deadline = System.currentTimeMillis() + REPLY_DELAY_TIMEOUT_MS
      var current = AivsSessionStore.get(did)
      while (current != null && !current.llmFinished && System.currentTimeMillis() < deadline) {
        kotlinx.coroutines.delay(REPLY_DELAY_POLL_MS)
        current = AivsSessionStore.get(did)
      }
      val finalState = AivsSessionStore.get(did)
      val instructions = args.firstOrNull() as? List<*>
      if (finalState == null || instructions == null) {
        replayOriginal(method, target, args, did, "session_or_args_missing")
        return@launch
      }
      if (
        finalState.llmFinished &&
        !finalState.llmAnswer.isNullOrBlank() &&
        !finalState.replacementConsumed &&
        !containsBlockedInstruction(instructions)
      ) {
        val replacedCount = rewriteTextPayloads(instructions, finalState.llmAnswer)
        if (replacedCount > 0) {
          AivsDebugSnapshotStore.reportReplacementPrepared(
            did = did,
            sessionId = finalState.sessionId,
            instructionSummary = instructionSummary,
            answerPreview = finalState.llmAnswer
          )
          HookLog.i(
            "AIVS reply replacement prepared did=$did sessionId=${finalState.sessionId} " +
              "replaced=$replacedCount answer=${finalState.llmAnswer.take(80)}"
          )
          replayOriginal(method, target, args, did, "replacement")
          val consumed = AivsSessionStore.markReplacementConsumed(did) ?: finalState
          AivsDebugSnapshotStore.reportReplacementCommitted(did, consumed.sessionId)
          HookLog.i(
            "AIVS reply replacement committed did=$did sessionId=${consumed.sessionId} " +
              "replaced=$replacedCount"
          )
          persistHistory(consumed)
          return@launch
        }
        AivsDebugSnapshotStore.reportOfficialReply(
          did = did,
          sessionId = finalState.sessionId,
          reason = "no_mutated_text_payload_after_delay",
          instructionSummary = instructionSummary
        )
        replayOriginal(method, target, args, did, "no_mutated_text_payload_after_delay")
        return@launch
      }
      val reason = when {
        !finalState.llmFinished -> "llm_timeout_before_reply"
        finalState.llmAnswer.isNullOrBlank() -> "llm_failed_or_empty_after_delay"
        finalState.replacementConsumed -> "already_replaced_after_delay"
        containsBlockedInstruction(instructions) -> "blocked_instruction_after_delay"
        else -> "replacement_not_ready_after_delay"
      }
      AivsDebugSnapshotStore.reportOfficialReply(
        did = did,
        sessionId = finalState.sessionId,
        reason = reason,
        instructionSummary = instructionSummary
      )
      replayOriginal(method, target, args, did, reason)
    }
  }

  private fun replayLowLevelSendWhenReady(
    method: Method,
    target: Any?,
    args: Array<Any?>,
    did: String,
    instructionSummary: String
  ) {
    val state = AivsSessionStore.get(did)
    val container = HookRuntimeResolver.resolve()
    if (container == null || state == null) {
      HookLog.w("AIVS low-level delay aborted did=$did reason=runtime_or_session_unavailable")
      replayOriginal(method, target, args, did, "runtime_or_session_unavailable")
      return
    }
    val batch = delayedLowLevelBatches.computeIfAbsent(did) {
      DelayedInstructionBatch(
        did = did,
        sessionId = state.sessionId,
        createdAt = System.currentTimeMillis()
      )
    }
    batch.items.add(DelayedInstruction(method, target, args, instructionSummary))
    HookLog.i(
      "AIVS low-level send delayed did=$did sessionId=${state.sessionId} " +
        "size=${batch.items.size} $instructionSummary"
    )
    if (!batch.started.compareAndSet(false, true)) {
      return
    }
    container.appScope.launch {
      val deadline = System.currentTimeMillis() + REPLY_DELAY_TIMEOUT_MS
      var current = AivsSessionStore.get(did)
      while (current != null && !current.llmFinished && System.currentTimeMillis() < deadline) {
        kotlinx.coroutines.delay(REPLY_DELAY_POLL_MS)
        current = AivsSessionStore.get(did)
      }
      kotlinx.coroutines.delay(REPLY_BATCH_GRACE_MS)
      flushDelayedLowLevelBatch(did)
    }
  }

  private fun flushDelayedLowLevelBatch(did: String) {
    val batch = delayedLowLevelBatches.remove(did) ?: return
    val finalState = AivsSessionStore.get(did)
    if (finalState == null) {
      batch.items.forEach { item -> replayOriginal(item.method, item.target, item.args, did, "session_missing_after_low_level_delay") }
      return
    }
    var replacedCount = 0
    if (
      finalState.llmFinished &&
      !finalState.llmAnswer.isNullOrBlank() &&
      !finalState.replacementConsumed
    ) {
      batch.items.forEach { item ->
        val instruction = item.args.firstOrNull()
        if (instruction != null && isLowLevelReplaceableTextInstruction(instruction)) {
          replacedCount += rewriteLowLevelTextPayload(instruction, finalState.llmAnswer)
        }
      }
    }
    val instructionSummary = batch.items.joinToString(" | ") { it.summary }.take(240)
    if (replacedCount > 0) {
      AivsDebugSnapshotStore.reportReplacementPrepared(
        did = did,
        sessionId = finalState.sessionId,
        instructionSummary = instructionSummary,
        answerPreview = finalState.llmAnswer.orEmpty()
      )
      HookLog.i(
        "AIVS low-level reply replacement prepared did=$did sessionId=${finalState.sessionId} " +
          "replaced=$replacedCount answer=${finalState.llmAnswer.orEmpty().take(80)}"
      )
      batch.items.forEach { item ->
        replayOriginal(item.method, item.target, item.args, did, "low_level_replacement")
      }
      val consumed = AivsSessionStore.markReplacementConsumed(did) ?: finalState
      AivsDebugSnapshotStore.reportReplacementCommitted(did, consumed.sessionId)
      HookLog.i(
        "AIVS low-level reply replacement committed did=$did sessionId=${consumed.sessionId} " +
          "replaced=$replacedCount delayed=${batch.items.size}"
      )
      persistHistory(consumed)
      return
    }
    val reason = when {
      !finalState.llmFinished -> "llm_timeout_before_reply"
      finalState.llmAnswer.isNullOrBlank() -> "llm_failed_or_empty_after_delay"
      finalState.replacementConsumed -> "already_replaced_after_delay"
      batch.items.none { item -> item.args.firstOrNull()?.let(::isLowLevelReplaceableTextInstruction) == true } ->
        "no_replaceable_low_level_text_instruction"
      else -> "no_mutated_text_payload_after_delay"
    }
    AivsDebugSnapshotStore.reportOfficialReply(
      did = did,
      sessionId = finalState.sessionId,
      reason = reason,
      instructionSummary = instructionSummary
    )
    batch.items.forEach { item ->
      val itemReason = when {
        !finalState.llmFinished -> "llm_timeout_before_reply"
        finalState.llmAnswer.isNullOrBlank() -> "llm_failed_or_empty_after_delay"
        finalState.replacementConsumed -> "already_replaced_after_delay"
        item.args.firstOrNull()?.let(::isLowLevelReplaceableTextInstruction) != true ->
          "no_replaceable_low_level_text_instruction"
        else -> "no_mutated_text_payload_after_delay"
      }
      replayOriginal(item.method, item.target, item.args, did, itemReason)
    }
  }

  private fun replayOriginal(method: Method, target: Any?, args: Array<Any?>, did: String, reason: String) {
    runCatching {
      XposedBridge.invokeOriginalMethod(method, target, args)
    }.onSuccess {
      HookLog.i("AIVS original send replayed did=$did reason=$reason")
    }.onFailure { throwable ->
      HookLog.w("AIVS original send replay failed did=$did reason=$reason", throwable)
      AivsDebugSnapshotStore.reportOfficialReply(
        did = did,
        sessionId = AivsSessionStore.get(did)?.sessionId ?: -1,
        reason = "replay_failed_${throwable.javaClass.simpleName}",
        instructionSummary = reason
      )
    }
  }

  private fun summarizeInstructionList(raw: Any?): String {
    val instructions = raw as? List<*> ?: return "instructions=[]"
    val preview = instructions.take(6).joinToString(", ") { summarizeInstruction(it) }
    val suffix = if (instructions.size > 6) ", ..." else ""
    return "instructions=[${preview}${suffix}]"
  }

  private fun triggerLlmIfNeeded(did: String) {
    val currentState = AivsSessionStore.get(did) ?: return
    if (currentState.llmStarted || currentState.asrBlacklisted) {
      return
    }
    var state = currentState
    val question = state.finalAsr?.trim().orEmpty()
    if (question.isBlank()) {
      AivsSessionStore.failLlm(did)
      AivsDebugSnapshotStore.reportLlmFailure(
        did = did,
        sessionId = state.sessionId,
        reason = "empty_final_asr"
      )
      HookLog.w("Skip LLM because final ASR is empty did=$did sessionId=${state.sessionId}")
      return
    }

    val container = HookRuntimeResolver.resolve()
    if (container == null) {
      AivsSessionStore.failLlm(did)
      AivsDebugSnapshotStore.reportLlmFailure(
        did = did,
        sessionId = state.sessionId,
        reason = "runtime_unavailable"
      )
      HookLog.w("Skip LLM because runtime container is unavailable did=$did sessionId=${state.sessionId}")
      return
    }

    val customCommandMatch = CustomCommandMatcher.match(question, container.configRepository.config.value)
    if (customCommandMatch != null) {
      val rule = customCommandMatch.rule

      HookLog.w(
        "AIVS custom command matched " +
                "did=$did " +
                "sessionId=${state.sessionId} " +
                "rule=${rule.id} " +
                "name=${rule.name} " +
                "pattern=${rule.pattern.take(80)}"
      )

      CustomCommandExecutor.executeAsync(
        customCommandMatch
      )

      return
    }

    val blacklistRule = AivsAsrBlacklistMatcher.firstMatch(question, container.configRepository.config.value)
    if (blacklistRule != null) {
      val rulePreview = blacklistRulePreview(blacklistRule)
      state = AivsSessionStore.markAsrBlacklisted(did, blacklistRule.id, rulePreview) ?: state
      AivsDebugSnapshotStore.reportAsrBlacklisted(did, state.sessionId, question, rulePreview)
      HookLog.i(
        "AIVS ASR blacklist matched did=$did sessionId=${state.sessionId} " +
          "rule=${blacklistRule.id} pattern=${blacklistRule.pattern.take(80)}"
      )
      return
    }

    state = AivsSessionStore.markLlmStarted(did) ?: return

    AivsDebugSnapshotStore.reportLlmStart(did, state.sessionId, question)
    HookLog.i("AIVS LLM start did=$did sessionId=${state.sessionId} question=${question.take(80)}")
    container.appScope.launch {
      runCatching {
        val config = container.configRepository.snapshot()
        val conversationState = container.conversationRepository.stateSnapshot()
        val activeConversation = conversationState.conversations.firstOrNull {
          it.id == conversationState.activeConversationId
        }
        val messages = conversationState.messages
          .filter { it.conversationId == conversationState.activeConversationId && it.content.isNotBlank() }
          .sortedBy { it.createdAt }
          .takeLast(CONVERSATION_CONTEXT_LIMIT)
        HookLog.i(
          "AIVS conversation context did=$did active=${conversationState.activeConversationId} " +
            "title=${activeConversation?.title.orEmpty()} messages=${messages.size}"
        )
        val memories = container.memoryRepository.enabledSnapshot(MEMORY_LIMIT)
        withTimeoutOrNull(LLM_TIMEOUT_MS) {
          container.llmClient.complete(
            config = config,
            messages = messages,
            memories = memories,
            userMessage = question
          )
        }
      }.onSuccess { result ->
        if (result == null) {
          AivsSessionStore.failLlm(did)
          AivsDebugSnapshotStore.reportLlmFailure(
            did = did,
            sessionId = state.sessionId,
            reason = "timeout"
          )
          HookLog.w("AIVS LLM timeout did=$did sessionId=${state.sessionId}")
          return@onSuccess
        }
        val updated = AivsSessionStore.completeLlm(did, result.answer, result.summary)
        AivsDebugSnapshotStore.reportLlmSuccess(
          did = did,
          sessionId = updated?.sessionId ?: state.sessionId,
          answer = result.answer
        )
        HookLog.i(
          "AIVS LLM success did=$did sessionId=${updated?.sessionId ?: state.sessionId} " +
            "answer=${result.answer.take(80)}"
        )
      }.onFailure { throwable ->
        AivsSessionStore.failLlm(did)
        AivsDebugSnapshotStore.reportLlmFailure(
          did = did,
          sessionId = state.sessionId,
          reason = "exception",
          error = throwable.message ?: throwable.javaClass.simpleName
        )
        HookLog.w("AIVS LLM failed did=$did sessionId=${state.sessionId}", throwable)
      }
    }
  }

  private fun evaluateReplacement(did: String, instructions: List<*>): ReplacementDecision {
    val state = AivsSessionStore.get(did)
      ?: return ReplacementDecision(reason = "no_session", logFallback = false)
    if (state.asrBlacklisted) {
      return ReplacementDecision(reason = "asr_blacklist", logFallback = containsTextReply(instructions), state = state)
    }
    if (state.finalAsr.isNullOrBlank()) {
      return ReplacementDecision(reason = "missing_final_asr", logFallback = containsTextReply(instructions))
    }
    if (!state.llmFinished) {
      return ReplacementDecision(reason = "llm_not_finished", logFallback = containsTextReply(instructions))
    }
    if (state.llmAnswer.isNullOrBlank()) {
      return ReplacementDecision(reason = "llm_failed_or_empty", logFallback = containsTextReply(instructions))
    }
    if (state.replacementConsumed) {
      return ReplacementDecision(reason = "already_replaced", logFallback = containsTextReply(instructions))
    }
    if (containsBlockedInstruction(instructions)) {
      return ReplacementDecision(reason = "blocked_instruction_batch", logFallback = true)
    }
    if (!containsTextReply(instructions)) {
      return ReplacementDecision(reason = "no_replaceable_text_instruction", logFallback = true)
    }
    return ReplacementDecision(
      shouldReplace = true,
      state = state,
      answer = state.llmAnswer,
      reason = "ready"
    )
  }

  private fun evaluateLowLevelReplacement(did: String, instruction: Any): ReplacementDecision {
    val state = AivsSessionStore.get(did)
      ?: return ReplacementDecision(reason = "no_session", logFallback = false)
    if (state.asrBlacklisted) {
      return ReplacementDecision(reason = "asr_blacklist", logFallback = isLowLevelTextLikeInstruction(instruction), state = state)
    }
    if (state.finalAsr.isNullOrBlank()) {
      return ReplacementDecision(reason = "missing_final_asr", logFallback = isLowLevelTextLikeInstruction(instruction))
    }
    if (!state.llmFinished) {
      return ReplacementDecision(reason = "llm_not_finished", logFallback = isLowLevelTextLikeInstruction(instruction))
    }
    if (state.llmAnswer.isNullOrBlank()) {
      return ReplacementDecision(reason = "llm_failed_or_empty", logFallback = isLowLevelTextLikeInstruction(instruction))
    }
    if (state.replacementConsumed) {
      return ReplacementDecision(reason = "already_replaced", logFallback = isLowLevelTextLikeInstruction(instruction))
    }
    if (!isLowLevelReplaceableTextInstruction(instruction)) {
      return ReplacementDecision(reason = "no_replaceable_low_level_text_instruction", logFallback = isLowLevelTextLikeInstruction(instruction))
    }
    return ReplacementDecision(
      shouldReplace = true,
      state = state,
      answer = state.llmAnswer,
      reason = "ready"
    )
  }

  private fun containsTextReply(instructions: List<*>): Boolean {
    return instructions.any { REPLACEABLE_FULL_NAMES.contains(invokeZeroArg(it, "getFullName")) }
  }

  private fun containsBlockedInstruction(instructions: List<*>): Boolean {
    return instructions.any { instruction ->
      val fullName = invokeZeroArg(instruction, "getFullName").orEmpty()
      val namespace = invokeZeroArg(instruction, "getNamespace").orEmpty()
      BLOCKED_FULL_NAMES.contains(fullName) || BLOCKED_NAMESPACES.contains(namespace)
    }
  }

  private fun rewriteTextPayloads(instructions: List<*>, replacement: String): Int {
    var replacedCount = 0
    instructions.forEach { instruction ->
      when (invokeZeroArg(instruction, "getFullName")) {
        "Template.Toast",
        "Template.General" -> {
          val payload = invokeAnyZeroArg(instruction, "getPayload") ?: return@forEach
          if (invokeSingleArg(payload, "setText", replacement)) {
            replacedCount += 1
          }
        }
      }
    }
    return replacedCount
  }

  private fun isLowLevelTextLikeInstruction(instruction: Any): Boolean {
    return lowLevelInstructionCode(instruction) in setOf(256, 257)
  }

  private fun isLowLevelReplaceableTextInstruction(instruction: Any): Boolean {
    return lowLevelInstructionCode(instruction) in setOf(256, 257)
  }

  private fun rewriteLowLevelTextPayload(instruction: Any, replacement: String): Int {
    return when (lowLevelInstructionCode(instruction)) {
      256 -> {
        val payload = readFieldValue(instruction, "f")
        if (payload != null && writeStringField(payload, "c", replacement.take(300))) 1 else 0
      }
      257 -> {
        val payload = readFieldValue(instruction, "g")
        if (payload != null && writeStringField(payload, "d", replacement)) 1 else 0
      }
      else -> 0
    }
  }

  private fun lowLevelInstructionCode(instruction: Any): Int {
    return (readFieldValue(instruction, "c") as? Number)?.toInt() ?: -1
  }

  private fun blacklistRulePreview(rule: AivsAsrBlacklistRule): String {
    val type = if (rule.regex) {
      if (rule.contains) "正则包含" else "正则全匹配"
    } else {
      if (rule.contains) "关键词包含" else "关键词等于"
    }
    return "$type: ${rule.pattern.take(120)}"
  }

  // 用于记录上次对话内容，防止重复记录
  private var lastContent = ""

  private fun persistHistory(state: AivsSessionState) {
    val question = state.finalAsr?.trim().orEmpty()
    val answer = state.llmAnswer?.trim().orEmpty()
    if (question.isBlank() || answer.isBlank()) {
      return
    }
    val sessionKey = "${state.did}_${state.sessionId}"
    // 如果已经存在，说明该对话内容已被底层 Hook 或高层 Hook 持久化过，直接拦截
    if (lastContent == question + answer) {
      HookLog.w("AIVS history persistence skipped (already persisted) for finalAsr=$question, llmAnswer=$answer, key=$sessionKey")
      return
    }
    lastContent = question + answer
    HookLog.i("persistHistory called, question=$question, answer=$answer, key=$sessionKey")
    val container = HookRuntimeResolver.resolve() ?: run {
      HookLog.w("Skip history persistence because runtime container is unavailable did=${state.did}")
      return
    }
    container.appScope.launch {
      runCatching {
        container.conversationRepository.appendExchange(question, answer)
      }.onSuccess { conversation ->
        AivsDebugSnapshotStore.reportHistoryPersisted(state.did, state.sessionId)
        HookLog.i(
          "AIVS conversation persisted did=${state.did} sessionId=${state.sessionId} " +
            "conversation=${conversation.id} title=${conversation.title}"
        )
        //维护记忆内容
        maintainMemories(container, state, question, answer, conversation.id)
      }.onFailure { throwable ->
        AivsDebugSnapshotStore.reportHistoryPersistFailed(
          did = state.did,
          sessionId = state.sessionId,
          error = throwable.message ?: throwable.javaClass.simpleName
        )
        HookLog.w("AIVS history persistence failed did=${state.did} sessionId=${state.sessionId}", throwable)
      }
    }
  }

  private fun maintainMemories(
    container: RuntimeContainer,
    state: AivsSessionState,
    question: String,
    answer: String,
    sourceSessionId: String
  ) {
    container.appScope.launch {
      AivsDebugSnapshotStore.reportMemoryMaintenance(state.did, state.sessionId, "started")
      val result = runCatching {
        val config = container.configRepository.snapshot()
        val existing = container.memoryRepository.enabledSnapshot(MEMORY_LIMIT)
        val actions = withTimeoutOrNull(MEMORY_TIMEOUT_MS) {
          container.llmClient.extractMemoryActions(
            config = config,
            question = question,
            answer = answer,
            existingMemories = existing
          )
        }.orEmpty()
        if (actions.isEmpty()) {
          AivsDebugSnapshotStore.reportMemoryMaintenance(state.did, state.sessionId, "skipped")
          HookLog.i("AIVS memory maintenance skipped did=${state.did} sessionId=${state.sessionId}")
          0
        } else {
          container.memoryRepository.applyToolActions(actions, sourceSessionId)
        }
      }
      result.onSuccess { changed ->
        if (changed > 0) {
          AivsDebugSnapshotStore.reportMemoryMaintenance(state.did, state.sessionId, "success", changed)
          HookLog.i("AIVS memory maintenance success did=${state.did} sessionId=${state.sessionId} changed=$changed")
        }
      }.onFailure { throwable ->
        AivsDebugSnapshotStore.reportMemoryMaintenance(
          did = state.did,
          sessionId = state.sessionId,
          state = "failed",
          error = throwable.message ?: throwable.javaClass.simpleName
        )
        HookLog.w("AIVS memory maintenance failed did=${state.did} sessionId=${state.sessionId}", throwable)
      }
    }
  }
  private fun summarizeInstruction(raw: Any?): String {
    if (raw == null) {
      return "null"
    }
    val fullName = invokeZeroArg(raw, "getFullName") ?: raw.javaClass.name
    val payload = runCatching {
      raw.javaClass.getMethod("getPayload").invoke(raw)
    }.getOrNull()
    val text = extractPayloadText(payload)
    return if (text.isNullOrBlank()) {
      fullName
    } else {
      "$fullName{text=${text.take(80)}}"
    }
  }

  private fun extractPayloadText(payload: Any?): String? {
    if (payload == null) {
      return null
    }
    val directText = invokeZeroArg(payload, "getText")
    if (!directText.isNullOrBlank()) {
      return directText
    }
    val results = runCatching {
      payload.javaClass.getMethod("getResults").invoke(payload) as? List<*>
    }.getOrNull()
    if (!results.isNullOrEmpty()) {
      return results.asReversed()
        .mapNotNull { invokeZeroArg(it, "getText") }
        .firstOrNull { it.isNotBlank() }
    }
    return invokeZeroArg(payload, "getTitle")
  }

  private fun extractDidFromInstructionCapability(target: Any?): String? {
    val aivsService = invokeAnyZeroArg(target, "getAivsService") ?: return null
    return invokeZeroArg(aivsService, "getDid")
  }

  private fun invokeAnyZeroArg(target: Any?, methodName: String): Any? {
    if (target == null) {
      return null
    }
    return runCatching {
      target.javaClass.getMethod(methodName).invoke(target)
    }.getOrNull()
  }

  private fun invokeSingleArg(target: Any?, methodName: String, value: Any): Boolean {
    if (target == null) {
      return false
    }
    return runCatching {
      val method = target.javaClass.methods.firstOrNull { candidate ->
        candidate.name == methodName &&
          candidate.parameterTypes.size == 1 &&
          candidate.parameterTypes.first().isAssignableFrom(value.javaClass)
      } ?: target.javaClass.methods.firstOrNull { candidate ->
        candidate.name == methodName && candidate.parameterTypes.size == 1
      }
      if (method == null) {
        false
      } else {
        method.invoke(target, value)
        true
      }
    }.getOrDefault(false)
  }

  private fun invokeZeroArg(target: Any?, methodName: String): String? {
    return invokeAnyZeroArg(target, methodName)?.toString()
  }

  private fun methodSignature(method: Method?): String {
    if (method == null) {
      return "unknown_method"
    }
    return method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } + "):" + method.returnType.simpleName
  }

  private fun argTypes(args: Array<Any?>): String {
    return args.joinToString(prefix = "[", postfix = "]") { it?.javaClass?.simpleName ?: "null" }
  }

  private data class PendingReplacement(
    val did: String,
    val replacedCount: Int
  )

  private data class DelayedInstruction(
    val method: Method,
    val target: Any?,
    val args: Array<Any?>,
    val summary: String
  )

  private data class DelayedInstructionBatch(
    val did: String,
    val sessionId: Int,
    val createdAt: Long,
    val started: AtomicBoolean = AtomicBoolean(false),
    val items: MutableList<DelayedInstruction> = Collections.synchronizedList(mutableListOf())
  )

  private data class ReplacementDecision(
    val shouldReplace: Boolean = false,
    val state: AivsSessionState? = null,
    val answer: String? = null,
    val reason: String,
    val logFallback: Boolean = false
  )

  private companion object {
    const val VOICE_CALLBACK_CLASS = "com.xiaomi.fitness.aivs.init.AivsComponent\$VoiceCallbackImpl"
    const val VOICE_CONFIG_CLASS = "com.huami.bluetooth.profile.channel.module.voice.VoiceConfig"
    const val BASE_SPEECH_RECOGNIZER_MANAGER_CLASS =
      "com.xiaomi.fitness.aivs.base.BaseAivsSpeechRecognizerManager"
    const val BASE_AIVS_SERVICE_CLASS = "com.xiaomi.fitness.aivs.base.BaseAivsService"
    const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
    const val POST_BACK_CLASS = "com.xiaomi.ai.api.SpeechRecognizer\$PostBack"
    const val LLM_TIMEOUT_MS = 15_000L
    const val REPLY_DELAY_TIMEOUT_MS = 6_000L
    const val REPLY_DELAY_POLL_MS = 80L
    const val REPLY_BATCH_GRACE_MS = 160L
    const val CONVERSATION_CONTEXT_LIMIT = 6
    const val MEMORY_LIMIT = 20
    const val MEMORY_TIMEOUT_MS = 10_000L

    val INSTRUCTION_CAPABILITY_CLASSES = listOf(
      "com.xiaomi.fitness.aivs.huami.HuamiInstructionCapabilityImpl",
      "com.xiaomi.fitness.aivs.bluetooth.BluetoothInstructionCapabilityImpl"
    )

    val AIVS_SERVICE_CLASSES = listOf(
      "com.xiaomi.fitness.aivs.huami.HuamiAivsService",
      "com.xiaomi.fitness.aivs.bluetooth.BluetoothAivsService"
    )

    val REPLACEABLE_FULL_NAMES = setOf(
      "Template.Toast",
      "Template.General"
    )

    val DELAYABLE_ANSWER_FULL_NAMES = setOf(
      "Nlp.StartAnswer",
      "Template.Toast",
      "Template.General",
      "Nlp.FinishAnswer",
      "Dialog.Finish"
    )

    val LOW_LEVEL_DELAYABLE_CODES = setOf(
      4,
      5,
      256,
      257
    )

    val BLOCKED_FULL_NAMES = setOf(
      "Template.Weather",
      "CustomDirective.ExecuteDeviceSkill"
    )

    val BLOCKED_NAMESPACES = setOf(
      "Alerts",
      "WearableController",
      "PlaybackController",
      "NFC",
      "BrightnessController",
      "System",
      "Launcher"
    )
  }
}




