# 開発者向けメモ

## 構成

| モジュール | 内容 |
|---|---|
| `smb-core` | Kotlin/JVM 製の SMB2/3 サーバー (Android 非依存。Mac/PC 上でも動く) |
| `app` | Android アプリ (フォアグラウンドサービス・設定 UI・Bonjour 公開) |

### smb-core の対応範囲

- 方言: SMB 2.0.2 / 2.1 / 3.0 / 3.0.2 / 3.1.1 (3.1.1 は事前認証整合性 SHA-512)
- 認証: NTLMv2 (SPNEGO, MIC 検証あり)。NTLMv1 は拒否
- 署名: HMAC-SHA256 (2.x) / AES-128-CMAC (3.x)。クライアントが署名した要求には署名で応答
- ファイル操作: 開く/作成/読み書き/一覧/名前変更/削除/タイムスタンプ変更/サイズ変更、複合要求
- IPC$ + srvsvc (NetrShareEnumAll / NetrShareGetInfo) による共有一覧
- 未対応: 暗号化 (SMB3 encryption)、oplock/リース、変更通知、名前付きストリーム、サーバー側コピー

## 画面の細かい仕様

- **サーバー**: スクロールしない 1 画面。上に稼働状態と太字の「IP:ポート」(タップでコピー)、中央に地球のワイヤーフレームと軌道。つながっている端末が軌道の上に点で現れ、名前だけのタグが付いてくる。タグを押すと、その端末の IP・受信/送信の速さ・接続時間がポップアップする。下の帯に接続台数と合計の速さ、一番下に開始/停止ボタンを固定
- **設定**: 右上のボタンで下から出るシート。「共有」「認証」「ネットワーク」「接続のしかた」(Mac / iPhone の smb:// と Windows の net use) のアコーディオン。開始時に入力エラーがあると、その欄の区画を開いて出す
- テーマは 自動 (端末設定に従う) → ライト → ダーク の順に切り替わり、選択は保存される
- 端末名は接続してきた端末が名乗った名前 (Windows・Mac など)。名乗らない端末は IP アドレスで表示
- **通信量**: 直近7日の棒グラフはチップで「合計・受信・送信」を切り替え、棒をタップするとその日の値を表示。履歴は端末内に30日分保存
- **ログ**: 直近200行。ログイン失敗 (ユーザー名と理由)、想定外のエラー応答、切断の理由 (直前のコマンド) も記録する。端末内の `files/log.txt` にも保存し、512KB を超えたら 1 世代 (`log.1.txt`) だけ残して作り直す
- デザインはデザインシステム「AndSmb」(Plus Jakarta Sans / JetBrains Mono / Feather Icons) に準拠

## 接続まわりの補足

- アプリには Bonjour で公開できた名前のアドレスを表示する。ポートは Bonjour の情報に入っているので要らない。IP で指定すると、Finder の「ネットワーク」では IP の項目と端末名が別々に並ぶ
- macOS は共有一覧の取得に常に 445 番を使うため、ポート番号付きでは一覧表示できない。共有名まで含めて指定する必要がある
- Windows の「ネットワーク」一覧に出ないのは、機器探索 (WS-Discovery / NetBIOS) に対応していないため
- macOS は名前付きストリーム非対応のサーバーに `._ファイル名` (AppleDouble) を作る

## セキュリティ・制限の詳細

- Wi-Fi (または有線 LAN) の IPv4 アドレスだけで待ち受ける。モバイルデータ・VPN 側では受け付けず、Wi-Fi が切れる・アドレスが変わるとサーバーを自動で止める
- LAN 外のアドレス (プライベート・リンクローカル以外) からの接続は切る
- パスワードは 8 文字以上。同じ IP から 15 分以内に 5 回認証に失敗すると、その IP を 5 分間締め出す (失敗のたびに 1 秒待たせる)。Finder が最初に試す匿名ログインは数えない
- 共有フォルダ内のシンボリックリンクは、リンク先が共有フォルダの中にあるものだけ使える (外を指すリンクは一覧に出ず、開けない)
- 同時接続は 32 本まで。接続後 30 秒以内に認証しない接続と、フレーム本体が 30 秒以内に届かない接続は切る
- SD カード・USB メモリは Android 11 以降のみ (Android の制限でボリュームのルートは選べない)。Android 10 以前は内部ストレージのみ

## ビルド

```bash
./gradlew :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk`。

### Mac 上でサーバー本体だけ動かす

```bash
./gradlew :smb-core:run --args="/path/to/dir user password 4450"
```

環境変数 `SMB_DEBUG=1` でコマンドごとの結果をログに出す。

### テスト

```bash
./gradlew :smb-core:test
```

## 動作確認済み

- macOS 標準クライアント (`mount_smbfs`) → Mac 上の smb-core: SMB 3.1.1 + AES-CMAC 署名、SMB 2.1 + 署名必須
- macOS → Android エミュレータ (API 36) 上のアプリ: 読み書き・日本語名・名前変更・削除・50MB 往復 (SHA1 一致)
- macOS → Pixel 4a (Android 13) 実機に Wi-Fi 直接接続: SMB 3.1.1 + AES-CMAC 署名、読み書き・日本語名・名前変更・削除・50MB 往復 (SHA1 一致)、Bonjour で `PIXEL4A` として検出
  - 実測: アップロード 約 6.5 MB/s、ダウンロード 約 10 MB/s (50MB ファイル)
- Windows 11 → Pixel 4a 実機: `net use Z: \\<IP>\android /TCPPORT:4450 /USER:android` でマウントできた

## リリース APK の配布

`v*` タグを push すると GitHub Actions (`.github/workflows/release.yml`) が署名済み APK をビルドし、GitHub Releases に添付する。Actions タブから手動実行 (`workflow_dispatch`) するとリリースは作らず、Artifacts に APK だけ残す。

```bash
git tag v0.2.0 && git push origin v0.2.0
```

- versionName はタグ名 (`v` を除く)、versionCode はワークフローの実行番号
- 事前にリポジトリの Settings → Secrets and variables → Actions に次の 4 つを登録する
  - `ANDSMB_KEYSTORE_BASE64`: keystore を `base64 -i release.jks` した文字列
  - `ANDSMB_KEYSTORE_PASSWORD` / `ANDSMB_KEY_ALIAS` / `ANDSMB_KEY_PASSWORD`
- ローカルで署名付きビルドするときは、リポジトリ直下に `keystore.properties` (git 管理外) を置く

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

- keystore を失くすと、同じアプリとして更新を配れなくなる。リポジトリの外に必ずバックアップする
