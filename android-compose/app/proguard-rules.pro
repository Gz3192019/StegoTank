# 临时：AGP 9.4.1 自带 R8 在 optimize 阶段崩溃，先关闭字节码优化
-dontoptimize

# ---- kotlinx-serialization（Route 返回栈序列化 / 恢复）----
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.setgo.tank.navigation.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer<*> serializer(...);
}
