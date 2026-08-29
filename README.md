# karaoke-app

Tách ra từ ý tưởng thiết kế KaraokeSkill của aichatvn2, nhưng chạy độc lập —
không phụ thuộc Plugin System của aichatvn2. Riêng phần WebRTC/CallSkill vẫn
tái sử dụng (xem `app/src/main/java/com/karaokeapp/webrtc/README_PASTE_HERE.md`).

## Cách dùng khung này (sau khi giải nén)

1. Giải nén `karaoke-app.zip` vào thư mục bạn muốn, ví dụ ngang hàng với
   aichatvn2 trong `~/storage/documents/`.
2. Trong Termux:
   ```bash
   pkg install git gradle openjdk-17
   cd karaoke-app
   gradle wrapper          # tạo gradlew + gradle-wrapper.jar (cần mạng)
   ```
   Lệnh `gradle wrapper` cần chạy 1 lần để tải gradle-wrapper.jar thật —
   file `gradle/wrapper/gradle-wrapper.properties` trong zip chỉ mới khai
   báo version (8.7), chưa có jar thật vì môi trường tạo zip này không có
   mạng.
3. Copy phần CallSkill/WebRTC từ aichatvn2 vào
   `app/src/main/java/com/karaokeapp/webrtc/` theo ghi chú trong đó.
4. Build thử:
   ```bash
   ./gradlew assembleDebug
   ```

## Về icon và build.gradle

Đã tạo sẵn bản tối thiểu để project build được ngay — không cần thêm gì
mới bắt đầu:

- **Icon**: dùng adaptive icon dạng vector (`ic_launcher_background.xml` +
  `ic_launcher_foreground.xml`), không cần file PNG nên không lo vỡ hình ở
  các độ phân giải khác nhau. Khi nào có icon thật (thiết kế riêng), chỉ
  cần thay nội dung 2 file vector đó hoặc thêm mipmap PNG rồi trỏ lại.
- **build.gradle**: đã set `applicationId com.karaokeapp`, `minSdk 29`
  (bắt buộc vì `AudioPlaybackCaptureConfiguration` chỉ có từ Android 10),
  `compileSdk`/`targetSdk 34`. Bạn có thể đổi `applicationId` nếu muốn tên
  gói khác.

## Về GitHub username/password khi push project mới

**Không cần "xử lý lại" tài khoản** — cùng một tài khoản GitHub push được
bao nhiêu repo cũng được, không giới hạn theo project. Chỉ cần lưu ý:

- Nếu Termux của bạn **đã từng push aichatvn2 thành công bằng SSH key**
  (`git remote -v` ra dạng `git@github.com:...`), thì dùng y nguyên SSH
  key đó cho `karaoke-app`, không cần tạo lại gì.
- Nếu bạn push bằng **HTTPS + mật khẩu tài khoản GitHub thường**: GitHub
  đã **ngừng hỗ trợ password auth** từ 2021. Nếu trước giờ vẫn push được
  aichatvn2 bằng HTTPS, thực chất bạn đang dùng **Personal Access Token
  (PAT)** thay cho password, và git đã lưu nó qua credential helper
  (`git config --global credential.helper store` hoặc cache). Token đó
  dùng chung được cho mọi repo — không cần tạo token riêng cho
  karaoke-app, trừ khi token cũ đã hết hạn hoặc bạn giới hạn nó chỉ cho 1
  repo cụ thể lúc tạo.
- Kiểm tra nhanh đang dùng kiểu nào:
  ```bash
  cd ~/aichatvn2
  git remote -v
  ```
  - Bắt đầu bằng `git@github.com:` → SSH, không cần làm gì thêm.
  - Bắt đầu bằng `https://github.com/` → HTTPS, lần đầu push
    karaoke-app git có thể hỏi lại username + token (dán PAT vào ô
    password) — nhập 1 lần, sau đó nếu có credential helper nó sẽ nhớ
    luôn cho các repo khác.

## Kế hoạch triển khai (giữ nguyên theo thiết kế gốc)

- **Phase 1** — `audio/music/MusicInput.java`: chứng minh
  `AudioPlaybackCaptureConfiguration` lấy được PCM từ app phát nhạc
  (VD YouTube). Không đi tiếp nếu bước này thất bại.
- **Phase 2** — `audio/mic/MicInput.java`: lấy PCM từ mic (không dùng
  cấu hình voice-chat WebRTC mặc định vì AEC/NS/AGC làm biến dạng giọng
  hát), đo latency Mic → loa/Bluetooth.
- **Phase 3** — `audio/mixer/LowLatencyMixer.java`: trộn 2 nguồn PCM,
  chưa thêm hiệu ứng.
- **Phase 4** — `audio/processor/VocalProcessor.java`: EQ → Compressor →
  Reverb → Echo → Limiter, chỉ làm sau khi mixer ổn định.

Nguyên tắc: không coi mỗi phase là xong chỉ vì code compile — phải đo/test
thật (đặc biệt latency qua Bluetooth A2DP) trước khi qua phase tiếp theo.
