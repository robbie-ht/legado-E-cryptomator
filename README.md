# Legado-E Cryptomator

> 基于 [legado-E](https://github.com/Luoyacheng/legado-E)（开源阅读 Legado 的延续分支）改造的**加密版**。
> 原版全部阅读 / 书源功能保留，额外增加「密码解锁 + 数据全加密」：未输入密码时，书架、书名、封面、正文在磁盘上全部是密文。

原版功能（自定义书源、搜索发现、订阅、替换净化、本地 TXT/EPUB、朗读、缓存、备份恢复等）**全部保留**，本分支只增加加密层与导入开关，不改变原有使用方式。

---

## 加密特性

### 1. 每次启动必须输入密码

启动后先到解锁页，密码正确才解密数据库、进入书架。首次使用为「设置密码」。

### 2. 数据全部加密落盘

```
/data/data/com.robbieht.legado.release/files/novelVault/
├── vault.info                  ← 唯一未加密文件：KDF 参数、盐、被口令包裹的主密钥（不含任何书名）
└── vault/                      ← 加密文件夹
    ├── library.db              ← SQLCipher 加密数据库（书架、书源、阅读进度、替换规则）
    ├── books/<id>.bin          ← 本地导入书籍正文（AES-256-GCM）
    ├── books/<hash>/*.nb       ← 书源章节正文（AES-256-GCM）
    ├── covers/<id>.bin         ← 本地书封面（AES-256-GCM）
    └── legacy/*.bin            ← 首次启用时对旧明文数据的加密归档
```

- 口令派生：PBKDF2-HMAC-SHA256，200000 次迭代
- 主密钥：随机 256 位，用口令派生密钥以 AES-256-GCM 包裹
- 文件加密：AES-256-GCM，64KB 分块，块序号作 AAD（防重排、防截断）
- 数据库口令：由主密钥派生，与用户密码解耦（改密码无需重加密数据库）

### 3. 不输密码什么都看不到

- 未解锁时没有数据库句柄、内存里没有密钥，访问直接抛 `VaultLockedException`
- 所有界面在 `onCreate/onStart` 被 `VaultGate` 拦截并跳回解锁页，界面先置为不可见，防止书架闪现
- 退到后台 / 从最近任务划掉：立即关库、抹掉内存密钥、删除解密临时目录与缓存
- 屏幕熄灭：立即锁定

### 4. 导入即加密

本地 TXT/EPUB/PDF/MOBI/**MHT/MHTML**、压缩包内书籍、在线文件、WebDAV 下载，全部**流式加密**写入保险库，公共目录不留明文。

**MHT/MHTML 支持**：单文件网页（MIME multipart，内含 HTML + base64/quoted-printable 编码的图片资源）导入时自动抽取正文转成纯文本，再按 TXT 目录规则分章，可直接阅读、朗读、缓存。

### 5. 书源加书架自动加密缓存

书源书加入书架后自动缓存全本，章节正文加密写入 `books/<hash>/`，读取时解密。

### 6. 选文件、分享导入不会卡在密码上

- 在 App 内点「选择文件/目录」跳系统选择器时给 120 秒宽限，返回后无需重新输密码（屏幕熄灭立即作废）
- 从系统「分享 / 打开方式」把文件发给本 App 时，先停在解锁页；解锁后**自动重放原导入流程**

---

## 新增：自由导入

路径：**我的 → 其它设定 → 自由导入**（默认关闭）

开启后：

| 行为 | 说明 |
| --- | --- |
| 导入列表 | 不再限制后缀，`Books/` 目录下任何文件都会列出来（`.md`、`.log`、`.json`、无后缀等） |
| 导入处理 | 不支持的格式按**纯文本**解析（走 TXT 目录规则），未知后缀也能分章、阅读 |
| 外部打开 | 从文件管理器「打开方式 / 分享」发来的未知格式，会先提示「不受支持，是否继续打开」，确认后按文本打开 |

关闭时保持原版行为：只认 `txt/epub/umd/pdf/mobi/azw3/azw/zip/rar/7z`。

---

## 自动更新

解锁进入书架后会自动检查 GitHub Releases（每 24 小时一次，可在「其它设定 → 自动检查更新」关闭）：

1. 拉取 `robbie-ht/legado-E-cryptomator` 的最新 release
2. 从 APK 资源名 `legado-vault-<版本号>.apk` 解析版本，与本地 `versionName` 逐段比较
3. 有新版本弹出更新说明，点「DOWNLOAD」即下载并自动拉起系统安装界面（首次需允许「安装未知应用」）

版本号比较不依赖 tag 命名，因此历史 tag 格式不一致也不会误报。

## 使用

1. 首次打开：设置阅读密码（**忘记无法找回**，只能清空全部加密数据重来）
2. 添加书籍：书架菜单 → 新增本机（或从文件管理器「打开方式 / 分享」发到本 App）
3. 添加书源：「我的」→ 书源管理 → 导入本地 / 在线
4. 从后台回来、重新打开 App 都需要再输一次密码

### 与原版共存说明

- 包名 `com.robbieht.legado.release`，可与官方 Legado 同时安装，但**数据不互通**（官方是明文库，本版是加密库）
- 从官方版升级过来时，首次创建保险库会把旧明文数据库/缓存**加密归档**到 `vault/legacy/` 后再删除明文，不会静默丢数据

---

## 构建

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleAppDebug      # 调试包 com.robbieht.legado.debug
./gradlew :app:assembleAppRelease    # 正式包 com.robbieht.legado.release（需签名）
```

签名构建（自行生成 keystore）：

```bash
./gradlew :app:assembleAppRelease \
  -PRELEASE_STORE_FILE=/path/to/release.jks \
  -PRELEASE_STORE_PASSWORD=****** \
  -PRELEASE_KEY_ALIAS=legado \
  -PRELEASE_KEY_PASSWORD=******
```

环境要求：JDK 17、Android SDK Platform 36。

---

## 已知限制

- 密码遗失无法恢复，只能「清空保险库」重来
- 每章正文是一个独立加密文件，书越多 `books/` 下文件越多（内容仍是密文）
- 阅读时明文临时副本位于 `files/.vaultwork`，锁定时删除；阅读过程中该目录会短暂存在明文，属正常现象
- 加密实现为本项目自研（PBKDF2 + AES-GCM + SQLCipher），未集成 Cryptomator 官方库

---

## 致谢与许可

- 上游：[gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E)
- 数据库加密：[SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android)
- 本项目遵循 **GPL-3.0**，见 [LICENSE](LICENSE)

> 本软件不提供任何书籍内容，书源与书籍由使用者自行添加。请遵守当地法律法规与内容平台条款。