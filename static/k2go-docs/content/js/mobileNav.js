/*! Help+Manual WebHelp 3 Script functions
Copyright (c) 2015-2025 by Tim Green. All rights reserved. Contact: https://www.helpandmanual.com
*/

// Set up vibration support if available
navigator.vibrate = navigator.vibrate || navigator.webkitVibrate || navigator.mozVibrate || navigator.msVibrate;
hmWebHelp.funcs.doVibrate = function(duration) {
	if (!duration) duration = 20;
	if (navigator.vibrate) {
				navigator.vibrate(duration);
			} 
	};

// Mobile nav closer
hmWebHelp.closeTopNav = function() {
	$("div#unclicker").off(hmBrowser.touchstart + ".closemenu").hide();
	$("ul.topnav > li > a.current").removeClass("current");
	hmpage.$mHamMenuSub.hide();
	hmpage.$mHamMenu.slideUp("fast");
	$(document).off(hmBrowser.touchstart + '.closemenu');
};

// Update topic navigation links
	
hmWebHelp.mobNavLinks = function(args) {

		var $prevButton = $("div#mobnavprevious"),
			$topButton = $("div#mobnavhome"),
			$nextButton = $("div#mobnavnext");
			
			if (args.phf == "none") {
				$prevButton.addClass("off").removeClass("on").removeAttr("data-href").removeAttr("data-bs");
			} else {
				$prevButton.removeClass("off").addClass("on").attr("data-href",args.phf).attr("data-bs",args.pbs);
			}
			
			if (args.hhf == "none") {
				$topButton.addClass("off").removeClass("on").removeAttr("data-href").removeAttr("data-bs");
			} else {
				$topButton.removeClass("off").addClass("on").attr("data-href",args.hhf).attr("data-bs",args.hbs);
			}
			
			if (args.nhf == "none") {
				$nextButton.addClass("off").removeClass("on").removeAttr("data-href").removeAttr("data-bs");
			} else {
				$nextButton.removeClass("off").addClass("on").attr("data-href",args.nhf).attr("data-bs",args.nbs);
			}
	
		$("div.mobnav.off").off("click").off(hmBrowser.touchstart);
		$("div.mobnav.on").off("click").off(hmBrowser.touchstart).on("click", function(event){event.preventDefault(); event.stopPropagation();}).on(hmBrowser.touchstart, function(event) {
		hmWebHelp.tocNav({action: "set", href: $(this).attr('data-href'), bs: parseInt($(this).attr('data-bs'),10)}); 
	});
		
	}; // donavlinks

		// Initialize the toolbar references
		hmpage.$mToolbar = $("div#mob_toolbar_wrapper");
		hmpage.$mToolbarBody = $("div#mob_toolbar");
		hmpage.$mToolbarCombo = $("div#mob_toolbar_wrapper,div#mob_toolbar");
		hmpage.$navTopicCombo = $("div#navwrapper, div#topicbox");
		hmpage.$mToolbarHeight = $("div#mob_toolbar").height() + 2;
		hmpage.$navTabs = $("div#toolbar_updown, div#dragwrapper");
		hmpage.navOp = $(hmpage.$navTabs[0]).css("opacity");
		hmpage.showTimer = null;

		/*** Header Hamburger Menu ***/
		hmpage.$mHambutton = $("div#phone_hamburger_icon");
		hmpage.$mHamnavbutton = $("div#phone_mobnav_hamburger_icon");
		hmpage.$mHamMenu = $("div#header_menu");
		hmpage.$mHamMenuA = $("div#header_menu a");
		hmpage.$mHamMenuSub = $("ul.subnav");
		hmpage.$mHamMenuUl = $("ul.topnav, ul.subnav");
		hmpage.$mHamMenuWd = 0;
		
		
		//Get width of header hamburger menu from hidden items 
		hmpage.$mHamMenu.css({"visibility": "hidden"}).show();
		hmpage.$mHamMenu.prepend("<div id='sizecheck' style='font-weight: bold;'></div>");
		var $sizecheck = $("div#sizecheck");
		var paddingOffset = Math.ceil(parseFloat(($("ul.topnav li a").first().css("padding-right")),10)*2.5) + 10;
		hmpage.$mHamMenuA.each(function() {
			$sizecheck.html($(this).html());
			var tw = $sizecheck.outerWidth();
			hmpage.$mHamMenuWd = hmpage.$mHamMenuWd > tw ? hmpage.$mHamMenuWd : tw;
		});
		hmpage.$mHamMenu.attr("style","");
		$sizecheck.remove();
		hmpage.$mHamMenuWd += paddingOffset;
		hmpage.$mHamMenuWd = ((hmpage.$mHamMenuWd * 1.2) / parseFloat($("html").first().css("font-size"),10)).toFixed(3);
		hmpage.$mHamMenu.css("width",hmpage.$mHamMenuWd + "rem");
		
		hmWebHelp.funcs.mHamburgerDo = function(){
			if (hmpage.$mHamMenu.is(":hidden")) {
			
			hmpage.$mHamMenu.slideDown("fast", function(){
				$("ul.topnav > li:visible").last().not(".last").addClass("last");
			});
				if (!hmpage.navclosed && hmpage.topicleft) {
					hmWebHelp.pageDimensions.dragHandle(false);
				}
				
			$("div#unclicker").show().on(hmBrowser.touchstart + ".closemenu", function(){
				hmWebHelp.closeTopNav();
			});
			} else {
				hmWebHelp.closeTopNav();
			}
			hmWebHelp.funcs.doVibrate();
		};

	// Maintain visibility of mobile toolbars in case of nasty browser tabs, address bars etc...
	hmWebHelp.funcs.mobTBfix = function(){
		var tbOffset = $("div#pagewrapper").height() - window.innerHeight;
		tbOffset = tbOffset > 4 ? tbOffset : 0;
		//hmpage.$mToolbar.css("bottom",tbOffset + "px");
	    hmpage.$scrollBox.css("padding-bottom",tbOffset + "px");
		hmpage.$navwrapper.css("bottom",(tbOffset + hmpage.$mToolbar.height() + 9) + "px");
		$("body").scrollTop(0);
		if (hmBrowser.Flandscape()) {
			$("table.mobtoolbar").addClass("landscape");
			if (hmDevice.iphone) {
				hmpage.$mHamnavbutton.show();
				hmpage.$mHambutton.hide();
				}
			} else {
			$("table.mobtoolbar").removeClass("landscape");
				if (hmDevice.iphone) {
				hmpage.$mHamnavbutton.hide();
				hmpage.$mHambutton.show();
				}
			}
	};

		
		
		hmpage.$mHambutton.on(hmBrowser.touchstart,hmWebHelp.funcs.mHamburgerDo);
		hmpage.$mHamnavbutton.on(hmBrowser.touchstart,hmWebHelp.funcs.mHamburgerDo);
		
		// Make tabs transparent on touch

		$("body").on(hmBrowser.touchmove, function(){

		if (typeof hmpage.navOp == "undefined")
			hmpage.navOp = 0.3;
		else hmpage.navOp = parseFloat(hmpage.navOp);
			
		if (hmpage.navOp > 0.1 ) {
			hmpage.$navTabs.css("opacity","0.1");
			$("body").on(hmBrowser.touchend + ".navtabs", function(){
				hmpage.$navTabs.css("opacity","0.3");
				$("body").off(hmBrowser.touchend + ".navtabs");
				});
			if (hmDevice.winphone) {
				if (hmpage.showTimer === null) {
				hmpage.showTimer = setTimeout(function(){
						clearTimeout(hmpage.showTimer);
						hmpage.showTimer = null;
						hmpage.$navTabs.css("opacity","0.3");
				},2500);
				}
			}
			}
		});
		
		hmWebHelp.funcs.mobileUpDown = function(event){
			if (event)
				event.stopPropagation();
			hmWebHelp.closeTopNav();
			var hHeight = hmpage.Fpix2em(hmpage.$headerbox.height());
			if (hmpage.$mToolbar.attr('data') == 'open') {
				// HIDE
				hmpage.$mToolbarHeight = hmpage.$mToolbar.height();
				hmpage.$headerbox.animate({
					top: (-hHeight + "rem")
				}, 300, function(){
					$(this).hide();
				});
				hmpage.$navwrapper.animate({
					top: "0.7rem",
					bottom: "0"
				},300);
				hmpage.$topicbox.animate({
					top: "0",
					bottom: "0"
					},300);
				hmpage.$mToolbarCombo.animate({
					height: ( "-=" + hmpage.$mToolbarHeight + "px"),
					bottom: 0
					},300, function() {
						hmpage.$mToolbar.attr("data","closed");
						hmpage.$mToolbarBody.hide();
						hmWebHelp.funcs.mobTBfix();
						if (false && hmpage.hmPicture !== "")
							hmWebHelp.extFuncs('hmFeatureHeaderM',"resize");
						sessionVariable.setSV("headerState","closed");
						});
				$("div#featureheader").animate({
					top: 0
				});
				
			} else {
			// SHOW
			hmpage.$headerbox.show().animate({
					top: "0"
				}, 300);
			hmpage.$headerbox.slideDown(300);
			hmpage.$navwrapper.animate({
				top: (hmpage.Fpix2em(hmpage.FheaderHeight() + 7) + "rem"),//"35px",
				bottom: (hmpage.Fpix2em(hmpage.$mToolbarHeight + 7) + "rem")
				},300);
			hmpage.$topicbox.animate({
					top: (hmpage.Fpix2em(hmpage.FheaderHeight()) + "rem"),
					bottom: (hmpage.Fpix2em(hmpage.$mToolbarHeight) + "rem")
				},300);
			hmpage.$mToolbarBody.show();
			hmpage.$mToolbarCombo.animate({
					height: ( "+=" +hmpage.$mToolbarHeight + "px")
					},300, function() {
						hmpage.$mToolbar.attr("data","open");
						hmWebHelp.funcs.mobTBfix();
						if (false && hmpage.hmPicture !== "")
							hmWebHelp.extFuncs('hmFeatureHeaderM',"resize");
						sessionVariable.setSV("headerState","open");
						});
			$("div#featureheader").animate({
					top: hmpage.$headerbox.height()
				});
			}
		hmWebHelp.funcs.doVibrate();
		};
		
		// Show/hide header bar and mobile toolbar
		$("div#toolbar_updown").on(hmBrowser.touchstart, function(event){
			hmWebHelp.funcs.mobileUpDown(event);
			});

	// Assign functions to toolbar buttons
	(function(){

		function btnAnimate(btn) {
			$(btn).animate({
				opacity: "-=0.3"
			},200, function(){
				$(btn).animate({
				opacity: "+=0.3"
			},200, function(){
			if ($(btn).attr("style").length > 0) {
				var thisStyle = $(btn).attr("style").replace(/opacity:.*?;/,"");
				if (thisStyle !== "")
					$(btn).attr("style",thisStyle);
				else 
					$(btn).removeAttr("style");
			}
				});
			});
		}
		function doToc(src) {
			hmWebHelp.pageDimensions.dragHandle();
			btnAnimate(src);
			hmWebHelp.funcs.doVibrate();
		}
		
		function fSize(mode,src) {
			hmWebHelp.extFuncs('fontSize',[mode,'global']);
			btnAnimate(src);
			hmpage.$mToolbarHeight = $("div#mob_toolbar").height() + 2;
			hmpage.$navwrapper.css({
				top: (hmpage.Fpix2em(hmpage.FheaderHeight() + 7) + "rem"),
				bottom: (hmpage.Fpix2em(hmpage.$mToolbarHeight + 7) + "rem")
				});
			
			hmWebHelp.funcs.doVibrate();
		}
		
		function navButton(src,btn) {
			var navtarget = $(src).attr("href");
			if (hmFlags.hmCurrentPage === navtarget || typeof navtarget == "undefined") {
				return;
				}
			History.pushState(null,null,navtarget);
			btnAnimate(btn);
			hmWebHelp.funcs.doVibrate();
			} 

		$("div#mobnavtextplus").on(hmBrowser.touchstart, function(){fSize(true,"div#mobnavtextplus");});
		$("div#mobnavtextminus").on(hmBrowser.touchstart, function(){fSize(false,"div#mobnavtextminus");});
		$("div#mobnavtoc").on(hmBrowser.touchstart, function(){doToc("div#mobnavtoc");});
		$("div#dragwrapper").css("visibility","visible");
		$("a#topicnavlinkprevious,a#topicnavlinkhome,a#topicnavlinknext").removeAttr("href");
		
		hmpage.$scrollBox.on("scroll", function(){
			hmWebHelp.funcs.mobTBfix();
		});
		hmWebHelp.funcs.mobTBfix();

		
		hmpage.$topicbox.animate({
					top: (hmpage.Fpix2em(hmpage.headerheight) + "rem"),
					bottom: (hmpage.Fpix2em(hmpage.$mToolbarHeight) + "rem")
				},300);
		
		})();
