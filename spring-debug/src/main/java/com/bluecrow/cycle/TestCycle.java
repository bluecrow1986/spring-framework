package com.bluecrow.cycle;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author zhangq
 * @Package com.bluecrow.cycle
 * @Decription 生命周期的调试用例
 * @date 2022/4/28 12:02
 */
public class TestCycle {
	public static void main(String[] args) {
		// 创建一个XML的应用上下文
		ApplicationContext ac = new ClassPathXmlApplicationContext("cycle.xml");
		A bean = ac.getBean(A.class);
		System.out.println(bean.getB());
		System.out.println(bean.getName());
		B bean1 = ac.getBean(B.class);
		System.out.println(bean1.getA());

	}
}
