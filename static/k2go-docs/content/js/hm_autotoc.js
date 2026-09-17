/*! Help+Manual WebHelp 3 Script functions
Copyright (c) 2015-2025 by Tim Green. All rights reserved. Contact: https://www.helpandmanual.com
*/
function hmatoc() {

	// General variables
	var 
		self = this,
		atoc_tip = "Jump to:&nbsp;",
		atoc_minHeaders = parseInt("3",10),
		atoc_btntip_off = "No contents for this topic",
		atoc_btntip_on = "Contents of this topic",
		atoc_toptip = "Jump to top of page",
		atoc_top = "Scroll to Top of Page",
		atoc_linklimit = parseInt("45",10),
		thisAtoc = "empty",
		isATOC = false;

	
	// Check if the current page is a search results topic
	function SearchCheck()  {
	  var foundHilite = window.location.search.lastIndexOf("zoom_highlight") > 0;
	  if (!foundHilite) {
		var fontTags = document.getElementsByTagName("FONT");
		if (fontTags.length > 0) {
		  var hStyle = "";
		  for (var cCheck = 0; cCheck < fontTags.length; cCheck++) {
			hStyle = fontTags[cCheck].style.cssText;
			if (hStyle.indexOf("BACKGROUND-COLOR")===0) {
		  foundHilite = true;
			  break; 
			}
		  }
		}
	  }  return foundHilite;     
	} // SearchCheck
	
	// String fix functions
	function truncate(str, limit) {
	var bits, i;
		if (limit===0) return str;
		if ((limit > 0) && (limit <= 20)) limit = 20;
		bits = str.split('');
		if (bits.length > limit) {
		   for (i = bits.length - 1; i > -1; --i) {
			if (i > limit) {
			bits.length = i;
			}
			else if (' ' === bits[i]) {
			bits.length = i;
			break;
			}
		}
		bits.push('...');
		}
	return bits.join('');

	}
	function trim(s) {
		return s.replace(/^\s+|\s+$/g, "");
	}
	
	function htmlFix(hd) {
		heading = hd.replace(/\&/g, "&amp;");
		hd = hd.replace(/</g, "&lt;");
		hd = hd.replace(/>/g, "&gt;");
		return hd;
	}
	
	// Reposition ATOC menu
  function atocPos() {
	var pBody = $("div#hmpagebody");
	var headerPos = pBody.position().top;
	 $("div#autoTocWrapper").css("top", headerPos + "px");
  }

	function initAtoc() {
		
		var tocHeads,
			thisPara,
			linkText,
			thisParaClass,
			subStyle,
			fullLinkText,
			linkID,
			TOClink,
			autoTOCcontent,
			TOCbox,
			clickDelayA, 
			clickDelayB,
			topicParas,
			i,
			isToggle = false,
			toggleID = "data=\"\"",
			$HMToggles = $("span[class*=_atoc]").children("a.dropdown-toggle");

	
	// Get the headers with AutoTOC tags			
	topicParas = $("[class*='_atoc']").filter("[class^='p_']").add($HMToggles);// add("span[class='temp_atoc_'],span[class='temp_atocs_']");

		if (topicParas.length >= atoc_minHeaders) {
		tocHeads = [];
		for (i = 0; i < topicParas.length;i++) {
			isToggle = false;
			thisPara = topicParas[i];
			linkText = $(topicParas[i]).text();
			linkText = trim(linkText);
			linkText = htmlFix(linkText);
			thisParaClass = $(thisPara).attr("class");
			if (thisParaClass === "dropdown-toggle") {
				thisParaClass = $(thisPara).parent("span").attr("class");
				isToggle = true;
			}
			subStyle = (thisParaClass.indexOf("_atocs_") != -1);
			// Delete non-breaking space for brain-dead IE
			if (linkText.length == 1) linkText = linkText.replace(/\xa0/,"");

			if (linkText!=="") {
				isATOC = true;
				fullLinkText = linkText.replace(/\"/g,"'");
				linkText = truncate(linkText,atoc_linklimit);
				
				// Create a target for non-toggles, use toggle ID for toggles
				if (!isToggle) {
					linkID = "autoTOC"+i;
					thisPara.innerHTML = '<a id="'+linkID+'"></a>' + thisPara.innerHTML;
					toggleID = "data=\"\" ";
				} else {
					linkID = thisPara.id;
					toggleID = "data=\""+linkID+"\" ";
				}
				if (!subStyle) {
				   TOClink = '<li class="autoTOC mainlink" id="src_'+linkID+'" '+toggleID+'title="'+atoc_tip+fullLinkText+'"><span><svg class="atocicon" viewBox="0 0 27 32"><use xlink:href="#ok-squared"></use></svg></span><p class="autoTOC">'+linkText+'</p></li>';
					} else {
				   TOClink = '<li class="autoTOC subTOC mainlink" id="src_'+linkID+'" '+toggleID+'title="'+atoc_tip+fullLinkText+'"><span><svg class="atocicon" viewBox="0 0 27 32"><use xlink:href="#ok"></use></svg></span><p class="autoTOC subTOC">'+linkText+'</p></li>';
					}
				
					tocHeads.push(TOClink);
					}
		}

			} else return;
			
		// Build the AutoTOC if elements exist

		if ((tocHeads[0]) && (tocHeads[0]!=="")) {
		autoTOCcontent = "";
		TOCbox = document.getElementById("autoTocWrapper");
		for (i = 0; i < tocHeads.length;i++) {
			autoTOCcontent = autoTOCcontent + tocHeads[i];
			}
		autoTOCcontent = '<li id="toplink" class="autoTOC toplink" title="'+atoc_toptip+'"><span><svg class="atocicon" viewBox="0 0 27 32"><use xlink:href="#angle-circled-up"></use></svg></span><p class="autoTOC">'+atoc_top+'</p></li>' +autoTOCcontent;
		autoTOCcontent = '<div id="autoTocMiddle"><div id="autoTocInner"><ul id="autoTocList">' + autoTOCcontent + '</ul></div></div>';
		
		TOCbox.innerHTML = autoTOCcontent;

		// Jump to targets for main ATOC entries
		 $("li.mainlink").on(hmBrowser.touchstart + ".atoc_clicks", function() {
			var isSearch = SearchCheck(),
				cTarget = $(this).attr("id"),
				tTarget = cTarget.replace(/src_/,""),
				theTarget = $("a[id='"+tTarget+"']"),
				doToggle = $(this).attr("data");
			// alert(theTarget.parent().html());
	   // If there are toggles on the page close them all
	   if (($HMToggles.length!==null) && (!isSearch)) {
			// hmWebHelp.extFuncs('hmDoToggle',{method: 'hmToggleToggles', obj: {speed:0,mode:"collapse"}});
			// alert("pause...")
			if (doToggle !== "") {
			hmWebHelp.extFuncs("hmDoToggle",{method: "HMToggle", obj:{target:$("a#"+doToggle),speed:0,mode:"expand"}});
			}
				
			
	   }

	   // Scroll to the target with togglejump
	   hmWebHelp.scrollTopic(theTarget);
	   
		return false;
		});

		// Scroll to top of document function
		$("li.toplink").on(hmBrowser.touchstart + ".atoc_clicks", function () {
		var isSearch = SearchCheck();
		hmpage.$scrollBox.scrollTo(0, 600, {
			onAfter: function(){
				if (($HMToggles.length > 0) && (!isSearch)) {
				hmWebHelp.extFuncs('hmDoToggle',{method: 'hmToggleToggles', obj:{speed:0,mode:"collapse"}});
				}
			}
		});
		return false;
		});
		
		} // End Build ATOC

	} // initAtoc()
	
	function openMenu() {
		if ($("div#navigationmenu").is(":visible"))
			hmWebHelp.hamburgerMenu();	
		atocPos();
		$(window).on('resize.atocResize', function() {
				atocPos();
				});
		$("div#autoTocWrapper").slideDown("fast", function(){
				$("div#unclicker").on(hmBrowser.touchstart, function(){
				closeMenu();
				}).show();
			});
		}

	function closeMenu(speed) {
			var animation = "fast";
			if (typeof speed == "string" && speed == "snap") animation = 0;
			else if (typeof speed == "number") animation = speed;
			$("div#autoTocWrapper").slideUp(animation, function(){
			$(window).off('resize.atocResize');
			$("div#unclicker").off(hmBrowser.touchstart).hide();
				});
		}
	
	/*** Expose the main calling function as the return object ***/
	return function(speed) {
		
		if ($("div#unclicker").length < 1) 
			$("div#hmpagebody").prepend('<div id="unclicker" />');
		
		// Set up for current topic if necessary
		if ($("#autoTocWrapper").html() === "") {
			initAtoc();		
		}
		
		// Show or hide the Atoc menu
		// Click delay prevents immediate closing after opening
		if ($("div#autoTocWrapper").is(":hidden")) {
			clickDelayA = new Date().getTime();
			openMenu();
		} else {
			clickDelayB = new Date().getTime();
			if (clickDelayB - clickDelayA > 1000)
				closeMenu(speed);
			} 

		}; // End main calling function
} // Constructor

hmWebHelp.funcs.hm_autotoc = new hmatoc();
