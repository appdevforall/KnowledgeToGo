# :world_map: Knowledge To Go

**Knowledge To Go (K2Go)**, con la tecnología de **[Internet-in-a-Box (IIAB)](https://internet-in-a-box.org)**, permitirá que millones de personas en todo el mundo construyan sus propias bibliotecas familiares, ¡dentro de sus propios teléfonos!

A partir de abril de 2026, estas Apps de IIAB están soportadas:

* **Calibre-Web** (libros electrónicos y videos)
* **Kiwix** (Wikipedias, etc.)
* **Kolibri** (lecciones y cuestionarios)
* **IIAB Maps** (fotos satelitales, relieve, edificios)
* **Matomo** (métricas)
* **System Dashboard** (solo Android) — panel de control web escrito en TypeScript

El puerto predeterminado del servidor web es **8085**, por ejemplo:

```
http://localhost:8085/maps
```

## ¿Cuáles son los componentes actuales de Knowledge To Go?

* **App Knowledge To Go (K2Go)** — App nativa de Android completa para Instalar, Usar, Compartir y Construir tu configuración en tu bolsillo.
* **Wrapper para instalar IIAB (iiab-android)** — configura [`local_vars_android.yml`](https://github.com/iiab/iiab/blob/master/vars/local_vars_android.yml) y luego lanza el instalador de IIAB
* **Capa principal de portabilidad de IIAB** — modificaciones a través de IIAB y sus roles existentes, basado en el [PR #4122](https://github.com/iiab/iiab/pull/4122)
* **proot-distro service manager (PDSM)** — como systemd, pero para `proot_services`

## Documentación relacionada

* **Bootstrap de Android (en este repo):** [`termux-setup/README.md`](https://github.com/appdevforall/KnowledgeToGo/blob/main/archive/termux-setup/README.md)
* **Rol proot_services (en el repo principal de IIAB):** [`roles/proot_services/README.md`](https://github.com/iiab/iiab/blob/master/roles/proot_services/README.md)

---

# Manual de usuario
## Knowledge To Go (K2Go)

## 1. Introducción
Knowledge To Go (K2Go) es una aplicación móvil diseñada para ofrecer un sistema de servicios y contenidos educativos en línea para zonas sin conexión a internet.

Este desarrollo ha evolucionado desde sus versiones anteriores dependientes de Termux; esta nueva iteración es una aplicación todo en uno (administrador, instalador y visor) que permite que cualquier dispositivo Android se convierta en un servidor de contenido sin conexión (ejecutándose sobre Debian ARM), alojando herramientas vitales como Wikipedia (Kiwix), Kolibri, IIAB Maps interactivos, bibliotecas digitales e incluso un editor de código sin conexión (Code on the Go) con la tecnología de core IIAB.

<p align="center">
  <img src="docs/images/k2go-logo.png" alt="Logo de Knowledge To Go" width="220">
</p>


## 2. Interfaz principal y secciones

### Configuración inicial y permisos
Al abrir la aplicación por primera vez, aparecerá la pantalla de **Configuración inicial**. Esta interfaz es un paso obligatorio para asegurar que el servidor Debian y los módulos pesados puedan ejecutarse en el entorno Android sin restricciones.

La interfaz requiere la activación de 4 permisos especiales:

1. **Notificaciones push:** Permiten que la aplicación envíe alertas flotantes sobre el estado del servidor, errores o procesos de sincronización completados.

2. **Acceso al almacenamiento local:** Es el permiso más crítico. Almacena los archivos zim de Wikipedia, los libros de Calibre y los mapas descargados (que pueden superar los 50 GB). Sin este permiso no será posible respaldar el contenido fuera de la propia app hacia el almacenamiento normal.

3. **Mostrar sobre otras apps:** Permite que Knowledge To Go mantenga ventanas flotantes o procesos visuales activos mientras el usuario realiza otras tareas en el teléfono.

4. **Desactivar la optimización de batería:** Crucial para el rendimiento. Por defecto, Android cierra las aplicaciones que consumen muchos recursos en segundo plano para ahorrar batería. Al "desoptimizar" la app, se le concede al servidor permiso para ejecutarse de forma indefinida incluso con la pantalla apagada.

**Opciones adicionales en esta pantalla:**

* **Botón "Administrar todos los permisos":** Abre los ajustes nativos de Android del dispositivo, permitiéndote revisar a fondo los accesos concedidos o solucionar problemas si algún botón se queda atascado.

* **Idioma del contenido:** Un selector desplegable que te permite elegir el idioma nativo en el que se configurará el servidor.

<p align="center">
  <img src="docs/images/00-initial-setup-01.webp" alt="00-initial-setup-01" width="220">
  <img src="docs/images/00-initial-setup-02.webp" alt="00-initial-setup-02" width="220">
</p>

### Pestaña Estado: Monitoreo del sistema
Es la pantalla principal. Aquí puedes monitorear la salud de tu dispositivo y el estado del servidor.

* **Información del dispositivo:** Muestra el modelo del teléfono, la versión de Android, la arquitectura del dispositivo, el tiempo de actividad, el estado de la batería, el uso de almacenamiento y la conexión actual (Wi-Fi y Hotspot).

* **Estado del servidor:** Te permite ver si el servidor está fuera de línea o en línea, indicando la arquitectura del sistema operativo base que se ejecuta en segundo plano (p. ej., Debian ARM64).

* **Módulos disponibles:** Muestra los servicios disponibles e indica los que están actualmente instalados en tu dispositivo (Books, Code, Kiwix, Kolibri, IIAB Maps, System).

<p align="center">
  <img src="docs/images/01-status-dashboard-01.webp" alt="01-status-dashboard-01" width="220">
  <img src="docs/images/01-status-dashboard-02.webp" alt="01-status-dashboard-02" width="220">
  <img src="docs/images/01-status-dashboard-03.webp" alt="01-status-dashboard-03" width="220">
</p>


### Pestaña Usar: Explorador de contenido
Desde aquí puedes acceder e interactuar con los módulos que has instalado usando el navegador interno de la aplicación.

* **Iniciar/Detener servidor:** Botón principal para encender o apagar los servicios. Siempre se recomienda detener el servidor desde aquí antes de cerrar la app para evitar errores.

* **Explorar contenido:** Abre el visor integrado (sin barra de URL para evitar la navegación externa) donde encontrarás acceso directo a herramientas como Kiwix (Wikipedia sin conexión), mapas, libros y aplicaciones de programación.

* **Compartir acceso al contenido:** A través de Wi-Fi o Hotspot, es posible compartir el acceso al contenido del dispositivo con otros equipos de la red, mediante códigos QR fáciles de escanear.

* **Registro de conexión:** Un registro en tiempo real para visualizar los procesos y conexiones activas, ideal para depuración.

<p align="center">
  <img src="docs/images/02-use-launch-01.webp" alt="02-use-launch-01" width="220">
  <img src="docs/images/02-use-launch-02.webp" alt="02-use-launch-02" width="220">
  <img src="docs/images/02-use-launch-03.webp" alt="02-use-launch-03" width="220">
</p>


### Pestaña Instalar: Gestión de módulos
El centro de control para descargar y gestionar el tamaño y el contenido de tu servidor sin conexión. Requiere conexión a internet para la descarga inicial.

* **Instalación rápida:** Ofrece tres paquetes preconfigurados en 3 niveles, donde agregar contenido (ZIMs o Maps) es opcional.
    * **Básico:** Solo el software esencial: Kiwix e IIAB Maps.
    * **Estándar:** Un nivel adicional que incluye Kolibri, lo justo para aumentar el uso educativo con contenido extenso.
    * **Completo:** Todo el catálogo disponible (Books, Kiwix, Maps, etc.) que, al agregar contenido opcional, puede pesar más de 50 GB en algunos idiomas.
* **Mantenimiento y recuperación:** Herramientas para crear respaldos de tu sistema, restaurar respaldos anteriores, forzar la detención de procesos o realizar un reinicio base (borrando la instalación para una configuración manual).

* **Gestión de módulos:** Aunque los tres niveles cubren las principales herramientas educativas, puedes gestionar los módulos individualmente.
    * **Matomo (Analítica)** es totalmente compatible, pero no se instala por defecto en ningún nivel para ahorrar espacio y recursos, ya que generalmente no es esencial para los usuarios finales. Puedes instalarlo manualmente desde esta pestaña.

<p align="center">
  <img src="docs/images/03-install-fast-install-1.webp" alt="03-install-fast-install-1" width="220">
  <img src="docs/images/03-install-fast-install-2.webp" alt="03-install-fast-install-2" width="220">
  <br><br>
  <img src="docs/images/03-install-modules.webp" alt="03-install-modules" width="220">
  <img src="docs/images/03-install-warning.webp" alt="03-install-warning" width="220">
</p>

### Pestaña Enviar: Compartir el sistema
Te permite compartir contenido sin conexión con otros usuarios a tu alrededor, usando Wi-Fi local o el Hotspot del dispositivo.

* **Compartir sistema vs. Recibir sistema:** Puedes escanear o generar un código QR para transferir (copiar) el entorno a otro dispositivo; más abajo se explica.


* **Transferir vs. Acceder:** Al compartir, es vital no confundir dos opciones importantes:
    * **Conceder acceso (experiencia del cliente):** Permite que otros dispositivos consuman el contenido alojado en tu teléfono. Cuando un cliente lo escanea, su navegador web predeterminado se abrirá automáticamente mostrando una versión web del menú de Knowledge To Go. No se requiere instalar ninguna app del lado del cliente; incluso es posible navegar desde una computadora en la misma red.

    <p align="center">
      <img src="docs/images/04-share-access-01-welcome.webp" alt="04-share-access-01-welcome" width="220">
      <img src="docs/images/04-share-access-02-start.webp" alt="04-share-access-02-start" width="220">
      <img src="docs/images/04-share-access-03-qr.webp" alt="04-share-access-03-qr" width="220">
    </p>

    * **Transferir (clonación):** Transfiere archivos masivos (hasta decenas de gigabytes) usando la tecnología *rsync* para que el otro dispositivo obtenga una copia exacta e independiente del servidor original sin necesidad de Internet.

    <p align="center">
      <img src="docs/images/04-share-transfer-01.webp" alt="04-share-transfer-01" width="220">
      <img src="docs/images/04-share-transfer-02.webp" alt="04-share-transfer-02" width="220">
      <img src="docs/images/04-share-transfer-receive.webp" alt="04-share-transfer-receive" width="220">
    </p>


## 3. Casos de uso

### **Brindar acceso a Wikipedia en un aula sin internet**
Un docente de una zona rural necesita que sus estudiantes investiguen historia. El docente activa el "Hotspot" en su teléfono, inicia el servidor desde la pestaña **Usar** de Knowledge To Go y selecciona "Compartir acceso". Los estudiantes se conectan a la red del docente, escanean el código QR y pueden navegar toda la Wikipedia en su propio idioma (vía Kiwix) desde sus propios dispositivos sin consumir datos móviles.

### **Descargar un mapa específico para trabajo de campo en una zona con mala conectividad**
Un equipo de voluntarios viaja a un municipio rural donde se sabe que la cobertura celular es irregular. Antes de salir (mientras todavía tienen acceso a internet), van a la pestaña **Usar** y abren "Maps". Seleccionan la región específica que visitarán (FQR - Full Quality Regions) y ejecutan el comando de descarga vía el **System Dashboard**. Al llegar, pueden ver cómodamente el trazado de las calles y los puntos de interés locales sin conexión, evitando cargos de roaming y sin depender de un plan de datos móviles inestable.

### **Replicar el servidor en el teléfono de un colega**
Un promotor educativo viaja a una comunidad aislada. Se encuentra con un líder comunitario que tiene un teléfono compatible y quiere dejarle el sistema instalado. El promotor va a la pestaña **Enviar**, selecciona **Transferir**, y el líder escanea el código QR desde la pestaña **Recibir**. Una copia exacta de los 38 GB de contenido comienza a transferirse al nuevo teléfono de forma inalámbrica.

## 4. Funciones especiales y avanzadas

* **Gestión de restricciones de Android (Phantom Process Killer)**

    Para instalaciones limpias o transferencias masivas, Android (versiones 12 en adelante) suele cerrar procesos pesados en segundo plano. La pestaña de instalación detecta si tienes activadas las Opciones de desarrollador y te guía para conectar ADB y desactivar estas restricciones cuando sea necesario, asegurando que las descargas e instalaciones masivas no se interrumpan.

<p align="center">
  <img src="docs/images/05-adb-setup-01.webp" alt="05-adb-setup-01" width="220">
  <img src="docs/images/05-adb-setup-02.webp" alt="05-adb-setup-02" width="220">
  <img src="docs/images/05-adb-setup-03.webp" alt="05-adb-setup-03" width="220">
</p>

* **System Dashboard (Web)**

    Un panel de control exclusivo de K2Go que te permite gestionar descargas y elementos (como extraer regiones de mapas) desde el navegador web sin necesidad de usar líneas de comandos para Kiwix, Maps y Books desde repositorios conocidos.

<p align="center">
  <img src="docs/images/05-dashboard-01-landing.webp" alt="05-dashboard-01-landing" width="220">
  <img src="docs/images/05-dashboard-02-kiwix.webp" alt="05-dashboard-02-kiwix" width="220">
</p>

* **La terminal oculta**

    Para usuarios avanzados que necesitan interactuar directamente con el entorno Debian:

    1. Ve a la parte inferior de cualquier pestaña.

    2. Mantén presionado el pie de página (la sección que muestra la versión de la app) durante 3 segundos.

    3. Aparecerá una terminal minimalista deslizable.

        Desde aquí puedes acceder al sistema operativo Debian 13 que impulsa el servidor embebido, donde tendrás la capacidad de probar y ejecutar las herramientas de core IIAB dentro de PRoot e instalar una gran cantidad de paquetes desde los propios repositorios de Debian.

    4. Para ocultarla, simplemente presiona el botón de retroceso o realiza el gesto de retroceso varias veces, o desbloquéala en la parte superior y desliza el panel hacia abajo.

<p align="center">
  <img src="docs/images/05-shell-landing-01.webp" alt="05-shell-landing-01" width="220">
  <img src="docs/images/05-shell-landing-02.webp" alt="05-shell-landing-02" width="220">
</p>
