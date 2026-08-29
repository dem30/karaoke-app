# WebRTC / CallSkill - can copy tay tu aichatvn2

Toi (Claude) khong truy cap duoc repo aichatvn2 tren may ban, nen thu muc nay
dang de trong. Ban tu copy phan code CallSkill/WebRTC (signaling qua
/call_signal, PeerConnection setup...) tu aichatvn2 vao day.

Luu y khi tach:
- Chi lay phan can cho VOCAL TRANSPORT (Phone 2 mic -> WebRTC -> Phone 1 mixer),
  khong can keo theo UI/logic rieng cua tinh nang goi thoai (call history, UI
  danh ba...).
- Kiem tra ky AEC/NS/AGC co dang bat mac dinh trong config WebRTC khong - neu
  co, phai tat di cho nhanh karaoke (xem ghi chu trong tai lieu thiet ke goc).
- Neu aichatvn2 dang dung thu vien WebRTC nao (vd org.webrtc:google-webrtc),
  them dependency tuong ung vao app/build.gradle.
