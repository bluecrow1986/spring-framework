package com.bluecrow.cycle;

/**
 * @author zhangq
 * @Package com.bluecrow.cycle
 * @Decription
 * @date 2021/8/13 12:05
 */
public class Logger {
	public void recordBefore(){
		System.out.println("前切入 -> " + this);
	}

	public void recordAfter(){
		System.out.println("后切入 -> " + this);
	}
}
