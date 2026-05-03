<template>
  <div class="login-container">
    <div class="background-animation">
      <div class="bg-shape shape-1"></div>
      <div class="bg-shape shape-2"></div>
      <div class="bg-shape shape-3"></div>
    </div>
    
    <div class="container" :class="{ slide: isRegister }">
      <!-- 登录表单 -->
      <div class="panel login-panel">
        <div class="brand-section">
          <div class="logo-container">
            <div class="logo-icon">
              <svg viewBox="0 0 24 24" width="40" height="40"><path fill="currentColor" d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>
            </div>
            <div class="logo-text">
              <h2 class="title">运维工作台</h2>
              <p class="subtitle">Linux 节点 · 本地工具 · 审计与日志</p>
            </div>
          </div>
        </div>
        
        <div class="form-section">
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><User /></el-icon>
              </div>
              <input 
                type="text" 
                placeholder="请输入账号" 
                v-model="loginForm.username"
                @input="validateAccount('login')"
                class="form-input"
              >
            </div>
            <div class="validate-tip" :class="{show: loginForm.accountTip.show, error: loginForm.accountTip.error, success: loginForm.accountTip.success}">
              <el-icon v-if="loginForm.accountTip.error"><Close /></el-icon>
              <el-icon v-else-if="loginForm.accountTip.success"><Check /></el-icon>
              {{ loginForm.accountTip.text }}
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Lock /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showLoginPassword ? 'text' : 'password'" 
                  placeholder="请输入密码" 
                  v-model="loginForm.password"
                  @input="validatePassword('login')"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleLoginPassword">
                  <el-icon v-if="showLoginPassword"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: loginForm.pwdTip.show, error: loginForm.pwdTip.error, success: loginForm.pwdTip.success}">
              <el-icon v-if="loginForm.pwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="loginForm.pwdTip.success"><Check /></el-icon>
              {{ loginForm.pwdTip.text }}
            </div>
            <!-- 密码强度条 -->
            <div class="password-strength" :class="{show: loginForm.password.trim() !== ''}">
              <div class="strength-bar" :class="loginForm.strengthClass[0]"></div>
              <div class="strength-bar" :class="loginForm.strengthClass[1]"></div>
              <div class="strength-bar" :class="loginForm.strengthClass[2]"></div>
            </div>
          </div>
          
          <div class="form-footer">
            <div class="agreement">
              <label class="checkbox-container">
                <input type="checkbox" v-model="loginForm.agree">
                <span class="checkmark"></span>
                <span class="label-text">我已阅读并同意<a href="javascript:;" class="agreement-link">《用户服务协议》</a></span>
              </label>
            </div>
            <div class="forgot-password">
              <a href="javascript:;" class="forgot-link" @click="showForgotPassword = true">忘记密码？</a>
            </div>
          </div>
          
          <button class="btn login-btn" @click="handleLogin" :disabled="!loginForm.agree">
            <span>登录</span>
            <el-icon class="btn-icon"><ArrowRight /></el-icon>
          </button>
        </div>
      </div>

      <!-- 注册表单 -->
      <div class="panel register-panel">
        <div class="brand-section">
          <div class="logo-container">
            <div class="logo-icon">
              <svg viewBox="0 0 24 24" width="40" height="40"><path fill="currentColor" d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>
            </div>
            <div class="logo-text">
              <h2 class="title">运维工作台</h2>
              <p class="subtitle">Linux 节点 · 本地工具 · 审计与日志</p>
            </div>
          </div>
        </div>
        
        <div class="form-section">
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><User /></el-icon>
              </div>
              <input 
                type="text" 
                placeholder="请设置账号" 
                v-model="registerForm.username"
                @input="validateAccount('register')"
                class="form-input"
              >
            </div>
            <div class="validate-tip" :class="{show: registerForm.accountTip.show, error: registerForm.accountTip.error, success: registerForm.accountTip.success}">
              <el-icon v-if="registerForm.accountTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.accountTip.success"><Check /></el-icon>
              {{ registerForm.accountTip.text }}
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Lock /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showRegisterPassword ? 'text' : 'password'" 
                  placeholder="请设置密码（8位以上，需包含数字、英文、特殊字符中至少两类）" 
                  v-model="registerForm.password"
                  @input="validatePassword('register')"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleRegisterPassword">
                  <el-icon v-if="showRegisterPassword"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: registerForm.pwdTip.show, error: registerForm.pwdTip.error, success: registerForm.pwdTip.success}">
              <el-icon v-if="registerForm.pwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.pwdTip.success"><Check /></el-icon>
              {{ registerForm.pwdTip.text }}
            </div>
            <!-- 密码强度条 -->
            <div class="password-strength" :class="{show: registerForm.password.trim() !== ''}">
              <div class="strength-bar" :class="registerForm.strengthClass[0]"></div>
              <div class="strength-bar" :class="registerForm.strengthClass[1]"></div>
              <div class="strength-bar" :class="registerForm.strengthClass[2]"></div>
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Check /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showRegisterRepwd ? 'text' : 'password'" 
                  placeholder="请确认密码" 
                  v-model="registerForm.repwd"
                  @input="validateRepwd"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleRegisterRepwd">
                  <el-icon v-if="showRegisterRepwd"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: registerForm.repwdTip.show, error: registerForm.repwdTip.error, success: registerForm.repwdTip.success}">
              <el-icon v-if="registerForm.repwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.repwdTip.success"><Check /></el-icon>
              {{ registerForm.repwdTip.text }}
            </div>
          </div>
          
          <div class="agreement">
            <label class="checkbox-container">
              <input type="checkbox" v-model="registerForm.agree">
              <span class="checkmark"></span>
              <span class="label-text">我已阅读并同意<a href="javascript:;" class="agreement-link">《用户服务协议》</a></span>
            </label>
          </div>
          
          <button class="btn register-btn" @click="handleRegister" :disabled="!registerForm.agree">
            <span>注册</span>
            <el-icon class="btn-icon"><Check /></el-icon>
          </button>
        </div>
      </div>

      <!-- 滑动切换面板 -->
      <div class="slide-panel">
        <div class="slide-content" v-if="!isRegister">
          <div class="slide-icon">
            <el-icon><UserFilled /></el-icon>
          </div>
          <h3>还没有账号？</h3>
          <p>注册一个新账号，开始使用日志分析系统</p>
          <button class="btn switch-btn" @click="isRegister = true">
            <span>立即注册</span>
            <el-icon class="btn-icon"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="slide-content" v-else>
          <div class="slide-icon">
            <el-icon><Key /></el-icon>
          </div>
          <h3>已有账号？</h3>
          <p>登录您的账号，继续使用日志分析系统</p>
          <button class="btn switch-btn" @click="isRegister = false">
            <span>立即登录</span>
            <el-icon class="btn-icon"><ArrowRight /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </div>
  
  <!-- 忘记密码弹窗 -->
  <el-dialog
    v-model="showForgotPassword"
    title="忘记密码"
    width="400px"
    :close-on-click-modal="false"
    class="forgot-password-dialog"
  >
    <div class="forgot-password-form">
      <div class="form-group">
        <div class="input-container">
          <div class="input-icon">
            <el-icon><User /></el-icon>
          </div>
          <input 
            type="text" 
            placeholder="请输入账号" 
            v-model="forgotForm.username"
            @input="validateAccount('forgot')"
            class="form-input"
          >
        </div>
        <div class="validate-tip" :class="{show: forgotForm.accountTip.show, error: forgotForm.accountTip.error, success: forgotForm.accountTip.success}">
          <el-icon v-if="forgotForm.accountTip.error"><Close /></el-icon>
          <el-icon v-else-if="forgotForm.accountTip.success"><Check /></el-icon>
          {{ forgotForm.accountTip.text }}
        </div>
      </div>
      
      <div class="form-group">
        <div class="input-container">
          <div class="input-icon">
            <el-icon><Lock /></el-icon>
          </div>
          <div class="password-input-wrapper">
            <input 
              :type="showForgotPasswordInput ? 'text' : 'password'" 
              placeholder="请设置新密码" 
              v-model="forgotForm.newPassword"
              @input="validatePassword('forgot')"
              class="form-input password-input"
            >
            <button type="button" class="password-toggle" @click="toggleForgotPassword">
              <el-icon v-if="showForgotPasswordInput"><View /></el-icon>
              <el-icon v-else><Hide /></el-icon>
            </button>
          </div>
        </div>
        <div class="validate-tip" :class="{show: forgotForm.pwdTip.show, error: forgotForm.pwdTip.error, success: forgotForm.pwdTip.success}">
          <el-icon v-if="forgotForm.pwdTip.error"><Close /></el-icon>
          <el-icon v-else-if="forgotForm.pwdTip.success"><Check /></el-icon>
          {{ forgotForm.pwdTip.text }}
        </div>
        <!-- 密码强度条 -->
        <div class="password-strength" :class="{show: forgotForm.newPassword.trim() !== ''}">
          <div class="strength-bar" :class="forgotForm.strengthClass[0]"></div>
          <div class="strength-bar" :class="forgotForm.strengthClass[1]"></div>
          <div class="strength-bar" :class="forgotForm.strengthClass[2]"></div>
        </div>
      </div>
      
      <div class="form-group">
        <div class="input-container">
          <div class="input-icon">
            <el-icon><Check /></el-icon>
          </div>
          <div class="password-input-wrapper">
            <input 
              :type="showForgotPasswordInput ? 'text' : 'password'" 
              placeholder="请确认新密码" 
              v-model="forgotForm.confirmPassword"
              @input="validateConfirmPassword"
              class="form-input password-input"
            >
            <button type="button" class="password-toggle" @click="toggleForgotPassword">
              <el-icon v-if="showForgotPasswordInput"><View /></el-icon>
              <el-icon v-else><Hide /></el-icon>
            </button>
          </div>
        </div>
        <div class="validate-tip" :class="{show: forgotForm.confirmTip.show, error: forgotForm.confirmTip.error, success: forgotForm.confirmTip.success}">
          <el-icon v-if="forgotForm.confirmTip.error"><Close /></el-icon>
          <el-icon v-else-if="forgotForm.confirmTip.success"><Check /></el-icon>
          {{ forgotForm.confirmTip.text }}
        </div>
      </div>
    </div>
    
    <template #footer>
      <div class="dialog-footer">
        <button class="btn cancel-btn" @click="showForgotPassword = false">
          <span>取消</span>
        </button>
        <button class="btn reset-btn" @click="handleResetPassword" :disabled="isResetting">
          <span>{{ isResetting ? '重置中...' : '重置密码' }}</span>
        </button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Lock, Check, Close, View, Hide, ArrowRight, UserFilled, Key } from '@element-plus/icons-vue'
import { login, register, checkUserExists, resetPassword } from '../api'

// 定义事件
const emit = defineEmits(['login-success'])

// 响应式数据
const isRegister = ref(false)
const showForgotPassword = ref(false)
const isResetting = ref(false)

// 密码显示/隐藏状态
const showLoginPassword = ref(false)
const showRegisterPassword = ref(false)
const showRegisterRepwd = ref(false)
const showForgotPasswordInput = ref(false)

// 登录表单数据
const loginForm = reactive({
  username: '',
  password: '',
  agree: false,
  accountTip: {
    show: false,
    error: false,
    success: false,
    text: '',
    timer: null // 存储自动隐藏定时器
  },
  pwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  strengthClass: ['', '', ''] // 密码强度条样式
})

// 忘记密码表单数据
const forgotForm = reactive({
  username: '',
  newPassword: '',
  confirmPassword: '',
  accountTip: {
    show: false,
    error: false,
    success: false,
    text: '',
    timer: null
  },
  pwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  confirmTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  strengthClass: ['', '', ''] // 密码强度条样式
})

// 注册表单数据
const registerForm = reactive({
  username: '',
  password: '',
  repwd: '',
  agree: false,
  accountTip: {
    show: false,
    error: false,
    success: false,
    text: '',
    timer: null // 存储自动隐藏定时器
  },
  pwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  repwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  strengthClass: ['', '', ''] // 密码强度条样式
})

// 切换密码显示/隐藏
const toggleLoginPassword = () => {
  showLoginPassword.value = !showLoginPassword.value
}

const toggleRegisterPassword = () => {
  showRegisterPassword.value = !showRegisterPassword.value
}

const toggleRegisterRepwd = () => {
  showRegisterRepwd.value = !showRegisterRepwd.value
}

const toggleForgotPassword = () => {
  showForgotPasswordInput.value = !showForgotPasswordInput.value
}

// 验证确认密码
const validateConfirmPassword = () => {
  const confirmPassword = forgotForm.confirmPassword.trim()
  
  forgotForm.confirmTip.show = false
  forgotForm.confirmTip.error = false
  forgotForm.confirmTip.success = false
  forgotForm.confirmTip.text = ''
  
  if (confirmPassword === '') return
  
  if (confirmPassword !== forgotForm.newPassword) {
    forgotForm.confirmTip.show = true
    forgotForm.confirmTip.error = true
    forgotForm.confirmTip.text = '两次输入的密码不一致'
  } else {
    forgotForm.confirmTip.show = true
    forgotForm.confirmTip.success = true
    forgotForm.confirmTip.text = '两次输入的密码一致'
  }
}

// 验证账号（取消长度限制，仅限制不能包含中文，正确提示2.5秒自动消失）
const validateAccount = (type) => {
  const form = type === 'login' ? loginForm : (type === 'forgot' ? forgotForm : registerForm)
  const account = form.username.trim()
  
  // 清除之前的自动隐藏定时器，避免重复触发
  if (form.accountTip.timer) clearTimeout(form.accountTip.timer)
  
  form.accountTip.show = false
  form.accountTip.error = false
  form.accountTip.success = false
  form.accountTip.text = ''
  
  if (account === '') return
  
  const chineseReg = /[\u4e00-\u9fa5]/
  const validReg = /^[a-zA-Z0-9!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]*$/
  
  if (chineseReg.test(account)) {
    form.accountTip.show = true
    form.accountTip.error = true
    form.accountTip.text = '账号不能包含中文'
  } else if (!validReg.test(account)) {
    form.accountTip.show = true
    form.accountTip.error = true
    form.accountTip.text = '账号只能包含数字、字母和常见特殊字符'
  } else {
    form.accountTip.show = true
    form.accountTip.success = true
    form.accountTip.text = '账号格式正确'
    // 2.5秒后自动隐藏提示
    form.accountTip.timer = setTimeout(() => {
      form.accountTip.show = false
    }, 2500)
  }
}

// 验证密码（8位以上，需包含数字、英文、特殊字符中至少两类）
const validatePassword = (type) => {
  const form = type === 'login' ? loginForm : (type === 'forgot' ? forgotForm : registerForm)
  const password = type === 'forgot' ? form.newPassword.trim() : form.password.trim()
  
  form.pwdTip.show = false
  form.pwdTip.error = false
  form.pwdTip.success = false
  form.pwdTip.text = ''
  form.strengthClass = ['', '', ''] // 重置强度条
  
  if (password === '') return
  
  // 正则定义
  const chineseReg = /[\u4e00-\u9fa5]/
  const validReg = /^[a-zA-Z0-9!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]*$/
  const numReg = /\d/ // 数字
  const letterReg = /[a-zA-Z]/ // 英文
  const specialReg = /[!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]/ // 特殊字符
  
  // 基础验证
  if (chineseReg.test(password)) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码不能包含中文'
    return
  }
  
  if (!validReg.test(password)) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码只能包含数字、字母和常见特殊字符'
    return
  }
  
  // 长度验证（建议8位以上，不强制但提示）
  if (password.length < 8) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = `密码长度${password.length}/8，建议至少8位`
  } else {
    // 计算符合的字符类型数量
    let typeCount = 0
    if (numReg.test(password)) typeCount++
    if (letterReg.test(password)) typeCount++
    if (specialReg.test(password)) typeCount++
    
    // 验证至少两类
    if (typeCount < 2) {
      form.pwdTip.show = true
      form.pwdTip.error = true
      form.pwdTip.text = '需包含数字、英文、特殊字符中至少两类'
    } else {
      form.pwdTip.show = true
      form.pwdTip.success = true
      // 根据类型数量显示强度
      if (typeCount === 2) {
        form.pwdTip.text = '密码强度：中等'
        form.strengthClass = ['medium', 'medium', '']
      } else {
        form.pwdTip.text = '密码强度：强'
        form.strengthClass = ['strong', 'strong', 'strong']
      }
    }
  }
  
  // 长度过长验证
  if (password.length > 20) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码长度不能超过20位'
  }
}

// 验证确认密码
const validateRepwd = () => {
  const repwd = registerForm.repwd.trim()
  
  registerForm.repwdTip.show = false
  registerForm.repwdTip.error = false
  registerForm.repwdTip.success = false
  registerForm.repwdTip.text = ''
  
  if (repwd === '') return
  
  if (repwd !== registerForm.password) {
    registerForm.repwdTip.show = true
    registerForm.repwdTip.error = true
    registerForm.repwdTip.text = '两次输入的密码不一致'
  } else {
    registerForm.repwdTip.show = true
    registerForm.repwdTip.success = true
    registerForm.repwdTip.text = '两次输入的密码一致'
  }
}

// 登录处理
const handleLogin = async () => {
  validateAccount('login')
  validatePassword('login')
  
  if (loginForm.username.trim() === '') {
    ElMessage.error('请输入账号')
    return
  }
  if (loginForm.password.trim() === '') {
    ElMessage.error('请输入密码')
    return
  }
  if (!loginForm.agree) {
    ElMessage.error('请同意用户服务协议')
    return
  }
  if (loginForm.accountTip.error || loginForm.pwdTip.error) {
    ElMessage.error('账号或密码格式不正确')
    return
  }
  
  try {
    // 调用登录 API
    const response = await login({
      username: loginForm.username,
      password: loginForm.password
    })
    
    // 登录成功，保存用户信息到 localStorage（仅保留必要字段）
    localStorage.setItem('user', JSON.stringify({
      userId: response.userId,
      username: response.username,
      role: response.role
    }))
    
    ElMessage.success('登录成功！')
    
    // 发出登录成功事件
    emit('login-success')
  } catch (error) {
    console.error('登录失败:', error)
    ElMessage.error('登录失败，请检查用户名和密码')
  }
}

// 注册处理
const handleRegister = async () => {
  validateAccount('register')
  validatePassword('register')
  validateRepwd()
  
  if (registerForm.username.trim() === '') {
    ElMessage.error('请设置账号')
    return
  }
  if (registerForm.password.trim() === '') {
    ElMessage.error('请设置密码')
    return
  }
  if (registerForm.repwd.trim() === '') {
    ElMessage.error('请确认密码')
    return
  }
  if (!registerForm.agree) {
    ElMessage.error('请同意用户服务协议')
    return
  }
  if (registerForm.accountTip.error || registerForm.pwdTip.error || registerForm.repwdTip.error) {
    ElMessage.error('表单填写有误，请检查')
    return
  }
  
  try {
    // 调用注册 API
    const response = await register({
      username: registerForm.username,
      password: registerForm.password
    })
    
    ElMessage.success('注册成功！')
    
    // 切换到登录表单
    isRegister.value = false
  } catch (error) {
    console.error('注册失败:', error)
    ElMessage.error('注册失败，请稍后重试')
  }
}

// 忘记密码处理
const handleResetPassword = async () => {
  validateAccount('forgot')
  validatePassword('forgot')
  validateConfirmPassword()
  
  if (forgotForm.username.trim() === '') {
    ElMessage.error('请输入账号')
    return
  }
  if (forgotForm.newPassword.trim() === '') {
    ElMessage.error('请设置新密码')
    return
  }
  if (forgotForm.confirmPassword.trim() === '') {
    ElMessage.error('请确认新密码')
    return
  }
  if (forgotForm.accountTip.error || forgotForm.pwdTip.error || forgotForm.confirmTip.error) {
    ElMessage.error('表单填写有误，请检查')
    return
  }
  
  try {
    isResetting.value = true
    
    console.log('开始重置密码流程')
    console.log('用户名:', forgotForm.username)
    
    // 检查用户是否存在
    console.log('检查用户是否存在...')
    const userExists = await checkUserExists({
      username: forgotForm.username
    })
    
    console.log('用户存在检查结果:', userExists)
    
    if (!userExists) {
      ElMessage.error('用户不存在，请检查账号')
      return
    }
    
    // 调用重置密码 API
    console.log('调用重置密码 API...')
    const success = await resetPassword({
      username: forgotForm.username,
      newPassword: forgotForm.newPassword
    })
    
    console.log('重置密码结果:', success)
    
    if (success) {
      ElMessage.success('密码重置成功！')
      showForgotPassword.value = false
      
      // 清空表单
      forgotForm.username = ''
      forgotForm.newPassword = ''
      forgotForm.confirmPassword = ''
      forgotForm.accountTip = {
        show: false,
        error: false,
        success: false,
        text: '',
        timer: null
      }
      forgotForm.pwdTip = {
        show: false,
        error: false,
        success: false,
        text: ''
      }
      forgotForm.confirmTip = {
        show: false,
        error: false,
        success: false,
        text: ''
      }
      forgotForm.strengthClass = ['', '', '']
    } else {
      ElMessage.error('密码重置失败，请稍后重试')
    }
  } catch (error) {
    console.error('重置密码失败:', error)
    ElMessage.error('重置密码失败，请稍后重试')
  } finally {
    isResetting.value = false
  }
}
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, 'Roboto', 'Microsoft Yahei', sans-serif;
}

/* 登录容器 */
.login-container {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #1a237e 0%, #283593 100%);
  position: relative;
  overflow: hidden;
}

/* 背景动画 */
.background-animation {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 0;
}

.bg-shape {
  position: absolute;
  border-radius: 50%;
  opacity: 0.1;
  animation: float 20s infinite ease-in-out;
}

.shape-1 {
  width: 300px;
  height: 300px;
  background: #4fc3f7;
  top: -50px;
  left: -50px;
  animation-delay: 0s;
}

.shape-2 {
  width: 200px;
  height: 200px;
  background: #29b6f6;
  bottom: -50px;
  right: -50px;
  animation-delay: -10s;
}

.shape-3 {
  width: 150px;
  height: 150px;
  background: #81d4fa;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  animation-delay: -5s;
}

@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  25% {
    transform: translate(50px, -50px) scale(1.1);
  }
  50% {
    transform: translate(0, 100px) scale(0.9);
  }
  75% {
    transform: translate(-50px, -50px) scale(1.05);
  }
}

/* 主容器 */
.container {
  width: 900px;
  height: 600px;
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  position: relative;
  overflow: hidden;
  z-index: 1;
  transition: all 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}

/* 面板样式 */
.panel {
  width: 50%;
  height: 100%;
  position: absolute;
  top: 0;
  transition: all 0.8s cubic-bezier(0.4, 0, 0.2, 1);
  padding: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.login-panel {
  left: 0;
  background: #fff;
}

.register-panel {
  right: 0;
  background: #fff;
}

/* 滑动面板 */
.slide-panel {
  width: 50%;
  height: 100%;
  position: absolute;
  top: 0;
  left: 50%;
  background: linear-gradient(135deg, #4fc3f7 0%, #29b6f6 100%);
  color: #fff;
  transition: all 0.8s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 60px;
  text-align: center;
  box-shadow: -5px 0 25px rgba(0, 0, 0, 0.1);
}

.container.slide .slide-panel {
  left: 0;
  box-shadow: 5px 0 25px rgba(0, 0, 0, 0.1);
}

.container.slide .login-panel {
  left: -50%;
}

.container.slide .register-panel {
  right: 0;
}

/* 品牌部分 */
.brand-section {
  padding: 40px 35px 20px;
  background: linear-gradient(135deg, #f8fafc 0%, #ffffff 100%);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.logo-container {
  display: flex;
  align-items: center;
  gap: 16px;
}

.logo-icon {
  color: #4fc3f7;
  display: flex;
  font-size: 28px;
  transition: transform 0.3s ease;
}

.logo-icon:hover {
  transform: scale(1.1) rotate(5deg);
}

.logo-text {
  display: flex;
  flex-direction: column;
}

.title {
  font-size: 28px;
  font-weight: 700;
  color: #1a237e;
  margin: 0 0 8px 0;
  text-shadow: 0 2px 4px rgba(26, 35, 126, 0.1);
}

.subtitle {
  font-size: 14px;
  color: #607d8b;
  margin: 0;
  font-weight: 400;
}

/* 表单部分 */
.form-section {
  flex: 1;
  padding: 35px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

/* 表单组 */
.form-group {
  margin-bottom: 24px;
  position: relative;
}

/* 输入容器 */
.input-container {
  position: relative;
  display: flex;
  align-items: center;
  background: #f8fafc;
  border: 2px solid #e2e8f0;
  border-radius: 12px;
  transition: all 0.3s ease;
  overflow: hidden;
}

.input-container:focus-within {
  border-color: #4fc3f7;
  box-shadow: 0 0 0 4px rgba(79, 195, 247, 0.1);
  background: #ffffff;
  transform: translateY(-2px);
}

/* 输入图标 */
.input-icon {
  padding: 0 16px;
  color: #94a3b8;
  font-size: 18px;
  transition: color 0.3s ease;
}

.input-container:focus-within .input-icon {
  color: #4fc3f7;
}

/* 表单输入 */
.form-input {
  flex: 1;
  height: 56px;
  padding: 0 16px;
  border: none;
  background: transparent;
  font-size: 16px;
  font-weight: 500;
  color: #263238;
  outline: none;
  transition: all 0.3s ease;
}

.form-input::placeholder {
  color: #94a3b8;
  font-weight: 400;
}

/* 密码输入包装器 */
.password-input-wrapper {
  flex: 1;
  position: relative;
}

.password-input {
  width: 100%;
  height: 56px;
  padding: 0 50px 0 0;
  border: none;
  background: transparent;
  font-size: 16px;
  font-weight: 500;
  color: #263238;
  outline: none;
}

/* 密码切换按钮 */
.password-toggle {
  position: absolute;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  color: #94a3b8;
  outline: none;
  transition: all 0.3s ease;
  padding: 8px;
  border-radius: 6px;
}

.password-toggle:hover {
  color: #4fc3f7;
  background: rgba(79, 195, 247, 0.1);
}

/* 验证提示 */
.validate-tip {
  font-size: 12px;
  height: 20px;
  line-height: 20px;
  margin-top: 8px;
  padding-left: 8px;
  visibility: hidden;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.3s ease;
}

.validate-tip.show {
  visibility: visible;
  animation: slideIn 0.3s ease;
}

.validate-tip.error {
  color: #ef4444;
}

.validate-tip.success {
  color: #10b981;
}

/* 表单底部 */
.form-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 16px 0 32px;
  font-size: 14px;
}

/* 协议复选框 */
.agreement {
  margin: 0;
  flex: 1;
}

/* 忘记密码 */
.forgot-password {
  margin-left: 20px;
}

.forgot-link {
  color: #4fc3f7;
  text-decoration: none;
  font-weight: 500;
  transition: all 0.3s ease;
  font-size: 14px;
}

.forgot-link:hover {
  color: #29b6f6;
  text-decoration: underline;
  transform: translateX(2px);
}

.checkbox-container {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
  user-select: none;
  transition: all 0.3s ease;
}

.checkbox-container:hover {
  transform: translateX(4px);
}

.checkbox-container input {
  position: absolute;
  opacity: 0;
  cursor: pointer;
  height: 0;
  width: 0;
}

.checkmark {
  position: relative;
  top: 2px;
  height: 20px;
  width: 20px;
  background-color: #f1f5f9;
  border: 2px solid #e2e8f0;
  border-radius: 6px;
  transition: all 0.3s ease;
}

.checkbox-container:hover input ~ .checkmark {
  background-color: #e0f2fe;
  border-color: #4fc3f7;
}

.checkbox-container input:checked ~ .checkmark {
  background-color: #4fc3f7;
  border-color: #4fc3f7;
}

.checkmark:after {
  content: "";
  position: absolute;
  display: none;
  left: 6px;
  top: 2px;
  width: 6px;
  height: 12px;
  border: solid white;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.checkbox-container input:checked ~ .checkmark:after {
  display: block;
}

.label-text {
  color: #607d8b;
  line-height: 1.4;
}

.agreement-link {
  color: #4fc3f7;
  text-decoration: none;
  font-weight: 500;
  transition: all 0.3s ease;
}

.agreement-link:hover {
  color: #29b6f6;
  text-decoration: underline;
}

/* 按钮样式 */
.btn {
  width: 100%;
  height: 56px;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  outline: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  position: relative;
  overflow: hidden;
}

.login-btn {
  background: linear-gradient(135deg, #4fc3f7 0%, #29b6f6 100%);
  color: #fff;
  box-shadow: 0 4px 16px rgba(79, 195, 247, 0.3);
}

.login-btn:hover:not(:disabled) {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(79, 195, 247, 0.4);
}

.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.register-btn {
  background: linear-gradient(135deg, #43a047 0%, #388e3c 100%);
  color: #fff;
  box-shadow: 0 4px 16px rgba(67, 160, 71, 0.3);
}

.register-btn:hover:not(:disabled) {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(67, 160, 71, 0.4);
}

.register-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.btn-icon {
  font-size: 18px;
  transition: transform 0.3s ease;
}

.btn:hover .btn-icon {
  transform: translateX(4px);
}

/* 切换按钮 */
.switch-btn {
  background: transparent;
  color: #fff;
  border: 2px solid rgba(255, 255, 255, 0.8);
  margin-top: 24px;
  width: 200px;
  border-radius: 28px;
  height: 52px;
  backdrop-filter: blur(8px);
}

.switch-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-3px);
  box-shadow: 0 4px 16px rgba(255, 255, 255, 0.2);
}

/* 滑动面板内容 */
.slide-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  animation: fadeIn 0.8s ease;
}

.slide-icon {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  color: white;
  backdrop-filter: blur(8px);
  box-shadow: 0 4px 16px rgba(255, 255, 255, 0.2);
  transition: all 0.3s ease;
}

.slide-icon:hover {
  transform: scale(1.1) rotate(5deg);
  box-shadow: 0 8px 25px rgba(255, 255, 255, 0.3);
}

.slide-panel h3 {
  font-size: 28px;
  margin: 0;
  color: white;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}

.slide-panel p {
  font-size: 16px;
  line-height: 1.6;
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  text-align: center;
}



/* 密码强度条 */
.password-strength {
  display: flex;
  gap: 4px;
  margin-top: 8px;
  height: 6px;
  visibility: hidden;
  border-radius: 3px;
  overflow: hidden;
}

.password-strength.show {
  visibility: visible;
  animation: slideIn 0.3s ease;
}

.strength-bar {
  flex: 1;
  border-radius: 3px;
  background: #e2e8f0;
  transition: all 0.3s ease;
}

.strength-bar.weak {
  background: #ef4444;
}

.strength-bar.medium {
  background: #f59e0b;
}

.strength-bar.strong {
  background: #10b981;
}

/* 滚动条 */
.panel::-webkit-scrollbar {
  display: none;
}

.panel {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

/* 动画 */
@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  25% {
    transform: translate(50px, -50px) scale(1.1);
  }
  50% {
    transform: translate(0, 100px) scale(0.9);
  }
  75% {
    transform: translate(-50px, -50px) scale(1.05);
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateX(-20px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 响应式设计 */
@media (max-width: 992px) {
  .container {
    width: 800px;
    height: 550px;
  }
  
  .form-section {
    padding: 30px;
  }
  
  .brand-section {
    padding: 35px 30px 15px;
  }
  
  .form-input {
    font-size: 15px;
  }
  
  .btn {
    height: 52px;
    font-size: 15px;
  }
}

@media (max-width: 850px) {
  .container {
    width: 90%;
    max-width: 700px;
    height: auto;
    min-height: 500px;
    margin: 40px 20px;
  }
  
  .panel {
    width: 100%;
    position: relative;
  }
  
  .login-panel {
    left: 0;
    z-index: 2;
  }
  
  .register-panel {
    right: 0;
    z-index: 1;
  }
  
  .slide-panel {
    width: 100%;
    left: 0;
    height: 300px;
    z-index: 3;
  }
  
  .container.slide .slide-panel {
    top: 100%;
  }
  
  .container.slide .login-panel {
    left: 0;
    top: -100%;
  }
  
  .container.slide .register-panel {
    right: 0;
    top: 0;
  }
  
  .form-section {
    padding: 25px;
  }
  
  .brand-section {
    padding: 30px 25px 15px;
  }
}

@media (max-width: 768px) {
  .login-container {
    padding: 16px;
  }
  
  .container {
    width: 100%;
    margin: 0;
    border-radius: 16px;
    min-height: 480px;
  }
  
  .form-section {
    padding: 20px;
  }
  
  .brand-section {
    padding: 25px 20px 12px;
  }
  
  .title {
    font-size: 24px;
  }
  
  .subtitle {
    font-size: 13px;
  }
  
  .slide-panel {
    padding: 30px;
    height: 280px;
  }
  
  .slide-panel h3 {
    font-size: 24px;
  }
  
  .slide-panel p {
    font-size: 14px;
  }
  
  .form-group {
    margin-bottom: 20px;
  }
  
  .form-input {
    height: 52px;
    font-size: 14px;
  }
  
  .btn {
    height: 52px;
    font-size: 15px;
  }
  
  .demo-account {
    margin-top: 20px;
    padding: 14px;
  }
  
  .demo-title {
    font-size: 13px;
  }
  
  .demo-info {
    font-size: 12px;
  }
  
  .form-footer {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  
  .forgot-password {
    margin-left: 0;
  }
  
  .label-text {
    font-size: 13px;
  }
  
  .forgot-link {
    font-size: 13px;
  }
  
  /* 忘记密码弹窗响应式 */
  .forgot-password-dialog {
    width: 90% !important;
    max-width: 400px;
  }
  
  .forgot-password-dialog .el-dialog__body {
    padding: 24px 20px;
  }
  
  .forgot-password-dialog .el-dialog__header {
    padding: 16px 20px;
  }
  
  .dialog-footer {
    padding: 0 20px 20px;
  }
}

@media (max-width: 480px) {
  .login-container {
    padding: 12px;
  }
  
  .container {
    border-radius: 12px;
    min-height: 450px;
  }
  
  .form-section {
    padding: 16px;
  }
  
  .brand-section {
    padding: 20px 16px 10px;
  }
  
  .logo-container {
    gap: 12px;
  }
  
  .logo-icon {
    font-size: 24px;
  }
  
  .title {
    font-size: 22px;
  }
  
  .subtitle {
    font-size: 12px;
  }
  
  .slide-panel {
    padding: 24px;
    height: 250px;
  }
  
  .slide-panel h3 {
    font-size: 22px;
  }
  
  .slide-panel p {
    font-size: 13px;
  }
  
  .switch-btn {
    width: 180px;
    height: 48px;
    font-size: 14px;
  }
  
  .form-group {
    margin-bottom: 16px;
  }
  
  .form-input {
    height: 48px;
    font-size: 14px;
  }
  
  .input-container {
    border-radius: 8px;
  }
  
  .input-icon {
    padding: 0 12px;
    font-size: 16px;
  }
  
  .btn {
    height: 48px;
    font-size: 14px;
    border-radius: 8px;
  }
  
  .validate-tip {
    font-size: 11px;
    margin-top: 6px;
  }
  
  .password-strength {
    margin-top: 6px;
    height: 4px;
  }
  
  /* 忘记密码弹窗响应式 */
  .forgot-password-dialog {
    width: 95% !important;
    max-width: 350px;
  }
  
  .forgot-password-dialog .el-dialog__body {
    padding: 20px 16px;
  }
  
  .forgot-password-dialog .el-dialog__header {
    padding: 14px 16px;
  }
  
  .forgot-password-dialog .el-dialog__title {
    font-size: 16px;
  }
  
  .dialog-footer {
    padding: 0 16px 16px;
    flex-direction: column;
    gap: 8px;
  }
  
  .dialog-footer .btn {
    width: 100%;
  }
}

@media (max-width: 360px) {
  .container {
    min-height: 420px;
  }
  
  .form-section {
    padding: 14px;
  }
  
  .brand-section {
    padding: 16px 14px 8px;
  }
  
  .title {
    font-size: 20px;
  }
  
  .subtitle {
    font-size: 11px;
  }
  
  .slide-panel {
    padding: 20px;
    height: 220px;
  }
  
  .slide-panel h3 {
    font-size: 20px;
  }
  
  .form-input {
    height: 44px;
    font-size: 13px;
  }
  
  .btn {
    height: 44px;
    font-size: 13px;
  }
  
  .label-text {
    font-size: 12px;
  }
}

/* 忘记密码弹窗 */
.forgot-password-dialog {
  border-radius: 12px;
  overflow: hidden;
}

.forgot-password-dialog .el-dialog__header {
  background: linear-gradient(135deg, #4fc3f7 0%, #29b6f6 100%);
  color: #fff;
  padding: 20px 24px;
  margin: 0;
}

.forgot-password-dialog .el-dialog__title {
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

.forgot-password-dialog .el-dialog__headerbtn .el-dialog__close {
  color: #fff;
  font-size: 20px;
}

.forgot-password-dialog .el-dialog__body {
  padding: 30px 24px;
}

.forgot-password-form .form-group {
  margin-bottom: 20px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 0 24px 24px;
}

.cancel-btn {
  background: #f1f5f9;
  color: #64748b;
  border: 1px solid #e2e8f0;
}

.cancel-btn:hover {
  background: #e2e8f0;
  transform: translateY(-2px);
}

.reset-btn {
  background: linear-gradient(135deg, #43a047 0%, #388e3c 100%);
  color: #fff;
  box-shadow: 0 4px 16px rgba(67, 160, 71, 0.3);
}

.reset-btn:hover:not(:disabled) {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(67, 160, 71, 0.4);
}

.reset-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}
</style>