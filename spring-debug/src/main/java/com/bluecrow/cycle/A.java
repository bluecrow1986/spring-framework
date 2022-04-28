package com.bluecrow.cycle;

/**
 * @author zhangq
 * @Package com.bluecrow.cycle
 * @Decription
 * @date 2021/8/13 12:04
 */
public class A {
	private String name;
	private B b;

	public B getB() {
		return b;
	}

	public void setB(B b) {
		this.b = b;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return "A{" +
				"b=" + b +
				'}';
	}

}
