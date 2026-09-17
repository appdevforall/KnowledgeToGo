/*! Help+Manual WebHelp 3 Script functions
Copyright (c) 2015-2025 by Tim Green. All rights reserved. Contact: https://www.helpandmanual.com
*/
var HMImageToggle = function($img){
	
	var	$imgbox, $imgzoomer, $page,
		$captionbox = false,
		imgID = $img.attr("id"),
		src0 = $img.attr("data-src0"),
		src1 = $img.attr("data-src1"),
		istate = $img.attr("data-state"),
		title0 = $img.attr("data-title0") ? $img.attr("data-title0") : null,
		title1 = $img.attr("data-title1") ? $img.attr("data-title1") : null,
		caption0 = $img.attr("data-caption0") ? $img.attr("data-caption0") : null,
		caption1 = $img.attr("data-caption1") ? $img.attr("data-caption1") : null,
		captionbox = caption0 ? $img.parents("div")[0] : null,
		startLeft = $img.offset().left,
		startTop = $img.offset().top,
		closeddims = {},
		maxdims = {},
		windowdims = {},
		maxImageHeight,
		vBorderWidth = 0,
		hFactor,
		scaleFactor,
		$zoomImage,
		newImage = new Image(),
		svgWrapper = document.createElement("object"),
		isSVG = src1.substr(src1.lastIndexOf("\.")) == "\.svg";
		
		// Create temporary full version to get dimensions
		newImage.src = src1;
		$(newImage).css({"visibility":"none","z-index": "100002"}).appendTo($("body"));
		
		// Get window dimensions and return dimensions
		windowdims.w = !hmDevice.phone ? $(window).width() - 30 : $(window).width();
		windowdims.h = !hmDevice.phone ? $(window).height() - 30 : $(window).height();
		closeddims.w = $img.width();
		closeddims.h = $img.height();
		maxImageHeight = windowdims.h;
		$("div#imagetogglebox").remove();
		
		// Get full dimenions and discard temp image
		maxdims.w = $(newImage).width();
		maxdims.h = $(newImage).height();
		$(newImage).remove();
		
		if (!hmDevice.phone)
			scaleFactor = windowdims.w / closeddims.w > windowdims.h / closeddims.h ? windowdims.h / closeddims.h : windowdims.w / closeddims.w;
		else {
			// Make toggle image smaller than viewport to avoid overflow bug
			// User can expand to view with pinch zoom 
			scaleFactor = (windowdims.w / closeddims.w) - 0.1;
			if ((closeddims.h * scaleFactor) > windowdims.h)
				scaleFactor = (windowdims.h / closeddims.h) - 0.1;
			
			}
		hFactor = closeddims.h/closeddims.w;
	
		$("body").append('<div id="imagetogglebox" style="position: absolute; top: '+startTop+'px; left: '+startLeft+'px; width:'+closeddims.w+'px; height:'+closeddims.h+'px;z-index:10000;"></div>');
		
		newImage = new Image();
		newImage.id = "zoomImg";
		newImage.src = src1;
		newImage.width = "100%";
		newImage.height = "auto";

		var $imgbox = $("div#imagetogglebox");
		
		if (!isSVG) {
			newImage.style = "width: 100%; height: auto; position: absolute; top: 0; left: 0 ;z-index:10000";
			$imgbox.append(newImage);
			
		} else {
			svgWrapper.type = "image/svg+xml";
			svgWrapper.width = "100%";
			svgWrapper.height = "auto";
			svgWrapper.style = "position: absolute; top: 0; left: 0 ;z-index:10000";
			svgWrapper.data = src1;
			$(svgWrapper).append(newImage);
			$imgbox.append(svgWrapper);
			}

		function closeImage(event,instant) {
			
			function rescale() {
				document.querySelector('meta[name="viewport"]').content = 'user-scalable=yes, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1, width=device-width, minimal-ui';
			}

			if (instant) {
				$imgbox.fadeOut("fast", function(){
					$(this).remove();
					rescale();
				});
			return;
			}
			$imgbox.animate({
			"top": startTop + "px",
			"left": startLeft+"px",
			"width": (closeddims.w) + "px",
			"height": (closeddims.h) + "px"
			
		},"fast", function(){
			$(this).remove();
			rescale();
		});
		}

		// For use if an SVG sends an Esc event
		window.addEventListener("message", function(event){
			if (event.data && event.origin === window.location.origin && event.data.type === "escapePressed") {
				closeImage();
			}
		});

		function maximizeImage() {
			$imgbox.animate({
			"top":"0",
			"left": "0",
			"width": maxdims.w + "px",
			"height": maxdims.h + "px"
			
		},"fast");
		}
		
	$imgzoomer = $("div#imagezoom");
	$imgbox = $("div#imagetogglebox");
	$page = $("div#pagewrapper");
	$zoomImage = $("img#zoomImg");
	
	if (!isSVG) $zoomImage.on("click",closeImage);
	
	var topTarget = hmDevice.phone ? 0 : (windowdims.h - (closeddims.h * scaleFactor)) / 2;
	var leftTarget = hmDevice.phone ? 0 : (windowdims.w - (closeddims.w * scaleFactor)) / 2;
	document.querySelector('meta[name="viewport"]').content = 'user-scalable=yes, initial-scale=1.0, maximum-scale=2.0, minimum-scale=0.5, width=device-width, minimal-ui';

		$zoomImage.show();
		$imgbox.show().animate({
			"top": topTarget + "px",
			"left": leftTarget + "px",
			"width": (closeddims.w * scaleFactor) + "px",
			"height": (closeddims.h * scaleFactor) + "px"
			
		},"fast", function(){
			if (isSVG) {
			$page.on("click.closer", function(){
				closeImage(event,true);
				$page.off("click.closer");
				});
			}
		});
};
hmWebHelp.funcs.hmImageToggleMobile = HMImageToggle;
