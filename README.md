# Altyazı Çıkar

TorBox WebDAV'ye bağlanır, klasörlerde gezdirir, bir `.mkv` seçtirir ve içine gömülü
altyazıları (ASS / SSA / SRT) **SRT'ye çevirip** telefondaki **X Video Player** klasörüne kaydeder
(ASS/SSA altyazılar anında SRT'ye dönüştürülür; stil/efekt etiketleri atılır, italik/kalın/altçizgi korunur).
İlk açılışta "tüm dosyalara erişim" izni istenir; verilmezse **İndirilenler/Altyazi**'ya kaydeder.
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
- **↑ Yukarı** düğmesinin yanındaki **arama kutusu** açık klasördeki klasör/dosya adlarını anında süzer.
- Her satırın solundaki **onay kutusuyla** tek tek seç (satıra uzun basmak da seçer);
  **Tümünü seç** görünen (aramaya uyan) öğelerin hepsini seçer.
- **Çıkar (N)**: seçilenlerden **sormadan doğrudan** altyazı çıkarmaya başlar (düğmeye uzun basarsan dil sorulur). Seçilen klasörler alt klasörleriyle taranıp içindeki tüm
  `.mkv`/`.webm` dosyaları işlenir. Dil varsayılan olarak **`eng`**'dir (uzun basıp değiştirirsen son dil hatırlanır;
  boş bırakırsan ilk altyazı alınır). Aynı dilde birden çok akış varsa "Signs/Songs/Forced" olmayan tam diyalog
  akışı tercih edilir.
- Bir `.mkv`'ye dokunursan altyazı akışları listelenir; İngilizce akış hazır işaretli gelir → **Çıkar**.
- Çıkan her altyazı, ASS/SSA ise otomatik **SRT**'ye çevrilir; zaten SRT ise olduğu gibi kaydedilir.
- Dosya adı `Video Adı.dil.srt` biçiminde olur; çoğu oynatıcı (MX Player, VLC, mpv) otomatik yükler.

## Sınırlar

- Yalnızca MKV/WebM. MP4 desteklenmez.
- Yalnızca metin altyazılar: ASS, SSA, SRT. Görüntü tabanlı PGS/VobSub listelenir ama çıkarılmaz.
- Altyazı blokları dosya boyunca dağınık olduğundan dosyanın tamamı okunur (diske kaydedilmez
  ama trafik harcar). Mümkünse Wi-Fi kullan.
- Şifre uygulamanın özel depolamasında düz metin saklanır; "Çıkış" bunu siler.

## TorBox Temizle (ayrı bölüm)

Gezgin ekranındaki **🗑 TorBox indirmelerini temizle** düğmesi ayrı bir ekran açar. TorBox **API anahtarını**
(TorBox → Settings → API Key) girersin; **İndirmeleri say** torrent / usenet / web indirme sayılarını gösterir,
**Tüm indirmeleri sil** onaydan sonra hesaptaki hepsini TorBox API'siyle siler (geri alınamaz).
