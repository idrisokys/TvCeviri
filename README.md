# Altyazı Çıkar

TorBox WebDAV'ye bağlanır, klasörlerde gezdirir, bir `.mkv` seçtirir ve içine gömülü
altyazıları (ASS / SSA / SRT) telefondaki **İndirilenler/Altyazi** klasörüne çıkarır.
Dosyayı telefona indirmez; akış olarak okur ve yalnızca altyazı bloklarını tutar.

## APK'yı GitHub'da derleme

1. GitHub'da yeni bir repo aç (public/private fark etmez).
2. Bu klasörün **içindekileri** repoya yükle (`.github` klasörü dahil). Git kullanıyorsan:
   ```
   git init && git add . && git commit -m "ilk"
   git branch -M main
   git remote add origin https://github.com/KULLANICI/REPO.git
   git push -u origin main
   ```
3. Repo → **Actions** sekmesi → "APK derle" iş akışı kendiliğinden başlar (yaklaşık 3-5 dk).
   Başlamadıysa Actions'ta iş akışını seçip **Run workflow** de.
4. Bitince APK iki yerde olur:
   - Repo → **Releases** → "Son sürüm" → `AltyaziCikar.apk` (telefondan doğrudan indirilir)
   - Actions → çalışmanın altında **Artifacts** → `AltyaziCikar-apk` (zip içinde)
5. APK'yı telefona indirip aç. "Bilinmeyen kaynaklardan yükle" izni istenirse tarayıcına/dosya yöneticine ver.

> Not: `.github` gizli klasör olduğu için web arayüzünden toplu yüklemede atlanabilir.
> Öyleyse repoda **Add file → Create new file** ile `.github/workflows/build.yml` adıyla
> oluşturup içeriği yapıştır.

## Kullanım

- Giriş: sunucu `https://webdav.torbox.app`, kullanıcı adı ve şifre TorBox hesabın
  (TorBox WebDAV için ayrı bir şifre/anahtar istiyorsa onu gir).
- Klasörde bir `.mkv`'ye dokun → altyazı akışları listelenir → seç → **Çıkar**.
- **Hepsinden çıkar**: açık klasördeki tüm `.mkv` dosyalarından, yazdığın dil koduna
  (`tur`, `eng`, `jpn`…) uyan ilk altyazıyı çıkarır. Boş bırakırsan ilk altyazıyı alır.
- Dosya adı `Video Adı.dil.ass` biçiminde olur; çoğu oynatıcı (MX Player, VLC, mpv) otomatik yükler.

## Sınırlar

- Yalnızca MKV/WebM. MP4 desteklenmez.
- Yalnızca metin altyazılar: ASS, SSA, SRT. Görüntü tabanlı PGS/VobSub listelenir ama çıkarılmaz.
- Altyazı blokları dosya boyunca dağınık olduğundan dosyanın tamamı okunur (diske kaydedilmez
  ama trafik harcar). Mümkünse Wi-Fi kullan.
- Şifre uygulamanın özel depolamasında düz metin saklanır; "Çıkış" bunu siler.
