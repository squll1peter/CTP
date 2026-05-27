function HorizontalSplit(leftDiv, sliderDiv, rightDiv, fillHeight,
							sliderPosition, forceTopForIE,
							leftMin, rightMin,
							changeHandler) {
	this.sliderWidth = 12;
	this.lmin = (leftMin ? leftMin : 120);
	this.rmin = (rightMin ? rightMin : 120);
	this.leftWidth = (sliderPosition ? sliderPosition : 300);

	this.fillHeight = fillHeight;
	this.forceTopForIE = forceTopForIE;
	this.changeHandler = changeHandler;

	this.left = document.getElementById(leftDiv);
	this.slider = document.getElementById(sliderDiv);
	this.right = document.getElementById(rightDiv);
	this.parent = this.left.parentNode;

	this.slider.hs = this;
	this.slider.onmousedown = startHorizontalDrag;
	this.slider.ondragstart = function() { return false; };
	this.slider.onselectstart = function() { return false; };

	this.positionSlider();
}

HorizontalSplit.prototype.positionSlider = function() {
	var bodyPos = findObject(document.body);
	var parentPos = findObject(this.parent);

	var top = parentPos.y;
	var height = parentPos.h;
	if (this.fillHeight) {
		var uncle = this.parent.nextSibling;
		while (uncle && (uncle.nodeType != 1)) uncle = uncle.nextSibling;
		if (uncle && (!uncle.style || !uncle.style.zIndex || (uncle.style.zIndex < 10))) {
			var unclePos = findObject(uncle);
			height = bodyPos.h - parentPos.y - unclePos.h;
		}
		else {
			height = bodyPos.h - parentPos.y;
		}
		if (height < 50) height = 50;
		this.parent.style.height = height + "px";
	}
	if (height < 50) height = 50;

	var w = parentPos.w;
	if (w <= 0) w = bodyPos.w;
	this.applyHorizontalLayout(top, height, w, this.leftWidth, true);
}

HorizontalSplit.prototype.clampLeftWidth = function(width, totalWidth) {
	var maxLeft = totalWidth - this.sliderWidth - this.rmin;
	if (maxLeft < this.lmin) maxLeft = this.lmin;
	if (width < this.lmin) width = this.lmin;
	if (width > maxLeft) width = maxLeft;
	if (width < 1) width = 1;
	return width;
}

HorizontalSplit.prototype.applyHorizontalLayout = function(top, height, totalWidth, leftWidth, notifyChange) {
	leftWidth = this.clampLeftWidth(leftWidth, totalWidth);
	this.leftWidth = leftWidth;

	this.left.style.top = top + "px";
	this.slider.style.top = top + "px";
	this.right.style.top = top + "px";

	this.left.style.height = height + "px";
	this.slider.style.height = height + "px";
	this.right.style.height = height + "px";

	this.left.style.left = 0;
	this.left.style.width = leftWidth + "px";

	this.slider.style.left = leftWidth + "px";
	this.slider.style.width = this.sliderWidth + "px";

	var rightLeft = leftWidth + this.sliderWidth;
	var rightWidth = totalWidth - rightLeft;
	if (rightWidth < 50) rightWidth = 50;
	this.right.style.left = rightLeft + "px";
	this.right.style.width = rightWidth + "px";

	if (notifyChange && this.changeHandler) this.changeHandler();
}

HorizontalSplit.prototype.toString = function() {
	var s = "";
	s += getDivParams("parent", this.parent);
	s += getDivParams("left", this.left);
	s += getDivParams("slider", this.slider);
	s += getDivParams("right", this.right);
	return s;
}

HorizontalSplit.prototype.setSlider = function(position) {
	this.leftWidth = position;
	this.positionSlider();
}

HorizontalSplit.prototype.moveSlider = function(increment) {
	this.leftWidth += increment;
	this.positionSlider();
}

HorizontalSplit.prototype.moveSliderTo = function(position) {
	var nsteps = 30;
	position = (position > this.lmin) ? position : this.lmin;
	var delta = (position - this.leftWidth) / nsteps;
	var n = 1;
	var interval = 1;
	var hs = this;
	move();

	function move() {
		if (n > nsteps) {
			hs.leftWidth = position;
			clearTimeout(timer);
		}
		else if (hs.leftWidth != position) {
			hs.leftWidth += delta;
			n++;
			timer = setTimeout(move, interval);
		}
		hs.positionSlider();
	}
}

function startHorizontalDrag(evt) {
	if (!evt) evt = window.event;
	var source = getSource(evt);
	var hs = source.hs;
	var sliderLeft = parseInt(hs.slider.style.left, 10);
	if (isNaN(sliderLeft)) sliderLeft = hs.leftWidth;
	var deltaX = evt.clientX - sliderLeft;
	var dragMask = createDragMask("col-resize");
	setIframesPointerEvents("none");
	document.body.style.userSelect = "none";
	hs.slider.classList.add("dragging");
	document.body.classList.add("is-dragging-split");
	var dragFrame = 0;
	var pendingLeft = hs.leftWidth;
	var dragContext = getHorizontalDragContext(hs);

	document.addEventListener("mousemove", dragSlider, true);
	document.addEventListener("mouseup", dropSlider, true);
	window.addEventListener("blur", endDrag, true);

	evt.stopPropagation();
	evt.preventDefault();
	return false;

	function dragSlider(e) {
		pendingLeft = (e.clientX - deltaX);
		if (!dragFrame) {
			dragFrame = requestNextFrame(function() {
				hs.applyHorizontalLayout(dragContext.top, dragContext.height, dragContext.width, pendingLeft, false);
				dragFrame = 0;
			});
		}
		e.stopPropagation();
		e.preventDefault();
		return false;
	}

	function dropSlider(e) {
		endDrag();
		if (!e) return false;
		e.stopPropagation();
		e.preventDefault();
		return false;
	}

	function endDrag() {
		document.removeEventListener("mouseup", dropSlider, true);
		document.removeEventListener("mousemove", dragSlider, true);
		window.removeEventListener("blur", endDrag, true);
		if (dragFrame) cancelNextFrame(dragFrame);
		hs.positionSlider();
		setIframesPointerEvents("auto");
		document.body.style.userSelect = "";
		hs.slider.classList.remove("dragging");
		document.body.classList.remove("is-dragging-split");
		removeDragMask(dragMask);
	}
}

function getHorizontalDragContext(hs) {
	var parentPos = findObject(hs.parent);
	var bodyPos = findObject(document.body);
	var top = parseInt(hs.left.style.top, 10);
	if (isNaN(top)) top = parentPos.y;
	var height = parseInt(hs.left.style.height, 10);
	if (isNaN(height) || (height < 50)) height = parentPos.h;
	if (height < 50) height = 50;
	var width = parentPos.w;
	if (width <= 0) width = bodyPos.w;
	return {
		top: top,
		height: height,
		width: width
	};
}

function VerticalSplit(topDiv, sliderDiv, bottomDiv,
						sliderPosition,
						topMin, bottomMin,
						changeHandler) {
	this.sliderHeight = 10;
	this.tmin = (topMin ? topMin : 120);
	this.bmin = (bottomMin ? bottomMin : 120);
	this.topHeight = (sliderPosition ? sliderPosition : 300);
	this.changeHandler = changeHandler;

	this.top = document.getElementById(topDiv);
	this.slider = document.getElementById(sliderDiv);
	this.bottom = document.getElementById(bottomDiv);
	this.parent = this.top.parentNode;

	this.slider.vs = this;
	this.slider.onmousedown = startVerticalDrag;

	this.positionSlider();
}

VerticalSplit.prototype.positionSlider = function() {
	var parentPos = findObject(this.parent);

	var left = parentPos.x;
	var width = parentPos.w;

	if (this.topHeight < this.tmin) this.topHeight = this.tmin;
	if (this.topHeight > parentPos.h - this.sliderHeight - this.bmin) this.topHeight = parentPos.h - this.sliderHeight - this.bmin;
	if (this.topHeight < 1) this.topHeight = 1;

	this.top.style.left = left + "px";
	this.slider.style.left = left + "px";
	this.bottom.style.left = left + "px";

	this.top.style.width = width + "px";
	this.slider.style.width = width + "px";
	this.bottom.style.width = width + "px";

	this.top.style.top = 0 + "px";
	this.top.style.height = this.topHeight + "px";

	this.slider.style.top = this.topHeight + "px";
	this.slider.style.height = this.sliderHeight + "px";

	var bt = this.topHeight + this.sliderHeight;
	var bh = parentPos.h - bt;
	if (bh < 1) bh = 1;
	this.bottom.style.top = bt + "px";
	this.bottom.style.height = bh + "px";

	if (this.changeHandler) this.changeHandler();
}

VerticalSplit.prototype.toString = function() {
	var s = "";
	s += getDivParams("parent", this.parent);
	s += getDivParams("top", this.top);
	s += getDivParams("slider", this.slider);
	s += getDivParams("bottom", this.bottom);
	return s;
}

function getDivParams(name, div) {
	var s = "";
	s += name+".id = "+div.id+"\n";
	s += name+".style.top = "+div.style.top+"\n";
	s += name+".style.left = "+div.style.left+"\n";
	s += name+".style.width = "+div.style.width+"\n";
	s += name+".style.height = "+div.style.height+"\n";
	s += name+".className = "+div.className+"\n\n";
	return s;
}

function startVerticalDrag(evt) {
	if (!evt) evt = window.event;
	var source = getSource(evt);
	var vs = source.vs;
	var sliderTop = parseInt(vs.slider.style.top, 10);
	if (isNaN(sliderTop)) sliderTop = vs.topHeight;
	var deltaY = evt.clientY - sliderTop;
	var dragMask = createDragMask("row-resize");
	setIframesPointerEvents("none");
	document.body.style.userSelect = "none";
	vs.slider.classList.add("dragging");
	document.body.classList.add("is-dragging-split");
	var dragFrame = 0;
	var pendingTop = vs.topHeight;

	document.addEventListener("mousemove", dragSlider, true);
	document.addEventListener("mouseup", dropSlider, true);
	window.addEventListener("blur", endDrag, true);

	evt.stopPropagation();
	evt.preventDefault();
	return false;

	function dragSlider(e) {
		pendingTop = (e.clientY - deltaY);
		if (!dragFrame) {
			dragFrame = requestNextFrame(function() {
				vs.topHeight = pendingTop;
				vs.positionSlider();
				dragFrame = 0;
			});
		}
		e.stopPropagation();
		e.preventDefault();
		return false;
	}

	function dropSlider(e) {
		endDrag();
		if (!e) return false;
		e.stopPropagation();
		e.preventDefault();
		return false;
	}

	function endDrag() {
		document.removeEventListener("mouseup", dropSlider, true);
		document.removeEventListener("mousemove", dragSlider, true);
		window.removeEventListener("blur", endDrag, true);
		if (dragFrame) cancelNextFrame(dragFrame);
		setIframesPointerEvents("auto");
		document.body.style.userSelect = "";
		vs.slider.classList.remove("dragging");
		document.body.classList.remove("is-dragging-split");
		removeDragMask(dragMask);
	}
}

function requestNextFrame(callback) {
	if (window.requestAnimationFrame) return window.requestAnimationFrame(callback);
	return setTimeout(callback, 16);
}

function cancelNextFrame(handle) {
	if (window.cancelAnimationFrame) window.cancelAnimationFrame(handle);
	else clearTimeout(handle);
}

function createDragMask(cursor) {
	var mask = document.createElement("div");
	mask.style.position = "fixed";
	mask.style.top = "0";
	mask.style.right = "0";
	mask.style.bottom = "0";
	mask.style.left = "0";
	mask.style.zIndex = "2147483647";
	mask.style.background = "transparent";
	mask.style.cursor = cursor;
	mask.setAttribute("aria-hidden", "true");
	document.body.appendChild(mask);
	return mask;
}

function removeDragMask(mask) {
	if (mask && mask.parentNode) mask.parentNode.removeChild(mask);
}

function setIframesPointerEvents(value) {
	var iframes = document.getElementsByTagName("iframe");
	for (var i = 0; i < iframes.length; i++) {
		iframes[i].style.pointerEvents = value;
	}
}